package lms.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.catalog.client.CatalogClient;
import lms.api.catalog.dto.CopyView;
import lms.api.catalog.dto.TitleView;
import lms.api.catalog.web.CatalogController;
import lms.api.circulation.client.CirculationClient;
import lms.api.circulation.dto.BorrowingView;
import lms.api.circulation.dto.FineView;
import lms.api.circulation.dto.ReaderFinesView;
import lms.api.circulation.web.BorrowingController;
import lms.api.circulation.web.FineController;
import lms.api.config.SecurityConfiguration;
import lms.api.identity.client.AccessClient;
import lms.api.identity.client.DepartmentClient;
import lms.api.identity.client.UserClient;
import lms.api.identity.dto.AccessDtos.RoleView;
import lms.api.identity.dto.DepartmentDtos.DepartmentNodeView;
import lms.api.identity.dto.DepartmentDtos.DepartmentView;
import lms.api.identity.dto.UserDtos.SearchUsersQuery;
import lms.api.identity.dto.UserDtos.UserPageView;
import lms.api.identity.dto.UserDtos.UserView;
import lms.api.identity.web.AccessController;
import lms.api.identity.web.DepartmentController;
import lms.api.identity.web.UserController;
import lms.api.support.client.SupportClient;
import lms.api.support.dto.AuditLogView;
import lms.api.support.dto.PageView;
import lms.api.support.web.AuditLogController;
import lms.api.web.ApiExceptionHandler;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/**
 * ADR-0008: các GET danh sách trong phạm vi nhận query tenantId và chuyển NGUYÊN xuống client (không kiểm claim,
 * không kiểm định dạng), vắng thì null; trường tenantId của phần tử ra tới FE.
 */
@WebMvcTest({UserController.class, DepartmentController.class, AccessController.class, CatalogController.class,
        BorrowingController.class, FineController.class, AuditLogController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class CrossTenantReadControllerTest {

    static final UUID T = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired MockMvc mvc;
    @MockitoBean UserClient users;
    @MockitoBean DepartmentClient departments;
    @MockitoBean AccessClient access;
    @MockitoBean CatalogClient catalog;
    @MockitoBean CirculationClient circulation;
    @MockitoBean SupportClient support;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void nguoiDungChuyenTenantIdVaTraTenantIdTrongPhanTu() throws Exception {
        when(users.search(any())).thenReturn(new UserPageView(
                List.of(new UserView(ID, "alice", "Alice", "a@x.vn", null, "ACTIVE", T)), 1, 0, 20));
        mvc.perform(get("/api/users").param("keyword", "ali").param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tenantId").value(T.toString()));
        ArgumentCaptor<SearchUsersQuery> q = ArgumentCaptor.forClass(SearchUsersQuery.class);
        verify(users).search(q.capture());
        assertEquals(T.toString(), q.getValue().tenantId());
        assertEquals("ali", q.getValue().keyword());

        mvc.perform(get("/api/users").with(jwt())).andExpect(status().isOk());
        verify(users, org.mockito.Mockito.times(2)).search(q.capture());
        assertNull(q.getValue().tenantId());
    }

    @Test
    void phongBanVaCayChuyenTenantIdNguyenChuoi() throws Exception {
        when(departments.list(any(), any())).thenReturn(List.of(new DepartmentView(ID, "A", "A", null, "ACTIVE", T)));
        when(departments.tree(eq(false), any())).thenReturn(
                List.of(new DepartmentNodeView(ID, "A", "A", "ACTIVE", List.of(), T)));
        mvc.perform(get("/api/departments").param("status", "ACTIVE").param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tenantId").value(T.toString()));
        verify(departments).list("ACTIVE", T.toString());
        mvc.perform(get("/api/departments").with(jwt())).andExpect(status().isOk());
        verify(departments).list(null, null);

        mvc.perform(get("/api/departments/tree").param("tenantId", "khong-phai-uuid").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tenantId").value(T.toString()));
        verify(departments).tree(false, "khong-phai-uuid");
        mvc.perform(get("/api/departments/tree").param("includeInactive", "true").with(jwt()))
                .andExpect(status().isOk());
        verify(departments).tree(true, null);
    }

    @Test
    void roleVaPermissionChuyenTenantId() throws Exception {
        when(access.listRoles(any())).thenReturn(List.of(new RoleView(ID, "r", "d", false, List.of(), T)));
        mvc.perform(get("/api/roles").param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tenantId").value(T.toString()));
        verify(access).listRoles(T.toString());
        mvc.perform(get("/api/roles").with(jwt())).andExpect(status().isOk());
        verify(access).listRoles(null);

        when(access.listPermissions(any())).thenReturn(List.of());
        mvc.perform(get("/api/permissions").param("tenantId", T.toString()).with(jwt())).andExpect(status().isOk());
        verify(access).listPermissions(T.toString());
        mvc.perform(get("/api/permissions").with(jwt())).andExpect(status().isOk());
        verify(access).listPermissions(null);
    }

    @Test
    void sachVaBanSaoChuyenTenantId() throws Exception {
        when(catalog.searchTitles(any(), eq(true), any())).thenReturn(List.of(
                new TitleView(ID, "Clean Code", "Martin", null, new BigDecimal("250000"), 2, T)));
        when(catalog.listCopies(eq(ID), any())).thenReturn(List.of(new CopyView(ID, ID, "CC-1", "AVAILABLE", T)));

        mvc.perform(get("/api/books").param("available", "true").param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tenantId").value(T.toString()));
        verify(catalog).searchTitles(null, true, T.toString());
        mvc.perform(get("/api/books").param("available", "true").with(jwt())).andExpect(status().isOk());
        verify(catalog).searchTitles(null, true, null);

        mvc.perform(get("/api/book-copies").param("titleId", ID.toString()).param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tenantId").value(T.toString()));
        verify(catalog).listCopies(ID, T.toString());
        mvc.perform(get("/api/book-copies").param("titleId", ID.toString()).with(jwt())).andExpect(status().isOk());
        verify(catalog).listCopies(ID, null);
        mvc.perform(get("/api/book-copies").param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void muonVaPhatTheoDocGiaChuyenTenantId() throws Exception {
        FineView fine = new FineView(ID, ID, ID, ID, "LATE", new BigDecimal("1000"), "UNPAID",
                Instant.parse("2026-01-20T00:00:00Z"), null, T);
        when(circulation.borrowingsOfReader(eq(ID), any())).thenReturn(List.of(new BorrowingView(ID, ID, "RETURNED",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15), 0, List.of(), List.of(), T)));
        when(circulation.finesOfReader(eq(ID), any())).thenReturn(
                new ReaderFinesView(ID, new BigDecimal("1000"), List.of(fine)));

        mvc.perform(get("/api/borrowings").param("readerId", ID.toString()).param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tenantId").value(T.toString()));
        verify(circulation).borrowingsOfReader(ID, T.toString());
        mvc.perform(get("/api/borrowings").param("readerId", ID.toString()).with(jwt())).andExpect(status().isOk());
        verify(circulation).borrowingsOfReader(ID, null);
        mvc.perform(get("/api/borrowings").param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/fines").param("readerId", ID.toString()).param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fines[0].tenantId").value(T.toString()));
        verify(circulation).finesOfReader(ID, T.toString());
        mvc.perform(get("/api/fines").param("readerId", ID.toString()).with(jwt())).andExpect(status().isOk());
        verify(circulation).finesOfReader(ID, null);
        mvc.perform(get("/api/fines").with(jwt())).andExpect(status().isBadRequest());
    }

    @Test
    void auditLogsChuyenTenantIdVaTraTenantIdTrongPhanTu() throws Exception {
        AuditLogView log = new AuditLogView(ID, ID, "BookBorrowedEvent", "agg", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:01Z"), "{}", T);
        when(support.searchAuditLogs(any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new PageView<>(List.of(log), 1, 0, 20));
        mvc.perform(get("/api/audit-logs").param("tenantId", T.toString()).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].tenantId").value(T.toString()));
        verify(support).searchAuditLogs(null, null, T.toString(), 0, 20);
        mvc.perform(get("/api/audit-logs").with(jwt())).andExpect(status().isOk());
        verify(support).searchAuditLogs(null, null, null, 0, 20);
    }

    @Test
    void saiDinhDangServiceTra400InvalidTenantIdThiBffChuyenNguyen() throws Exception {
        String body = "{\"code\":\"INVALID_TENANT_ID\",\"message\":\"tenantId không phải UUID\",\"details\":[]}";
        when(catalog.searchTitles(any(), eq(false), eq("abc"))).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        mvc.perform(get("/api/books").param("tenantId", "abc").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }
}
