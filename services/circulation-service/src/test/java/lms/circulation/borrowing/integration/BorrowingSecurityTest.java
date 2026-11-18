package lms.circulation.borrowing.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.circulation.borrowing.application.BorrowingResponse;
import lms.circulation.borrowing.application.BorrowBookUseCase;
import lms.circulation.borrowing.application.GetBorrowingUseCase;
import lms.circulation.borrowing.application.RenewBorrowingUseCase;
import lms.circulation.borrowing.application.ListBorrowingsUseCase;
import lms.circulation.borrowing.application.BorrowingPageResponse;
import lms.circulation.borrowing.application.SearchBorrowingsUseCase;
import lms.circulation.borrowing.application.ReportLostUseCase;
import lms.circulation.borrowing.application.ReturnBookUseCase;
import lms.circulation.borrowing.infrastructure.web.BorrowingController;
import lms.circulation.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm chứng RBAC: không token, thiếu permission, đủ permission. Không cần Docker. */
@WebMvcTest(BorrowingController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class BorrowingSecurityTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean BorrowBookUseCase borrowBook;
    @MockitoBean ReturnBookUseCase returnBook;
    @MockitoBean RenewBorrowingUseCase renewBorrowing;
    @MockitoBean GetBorrowingUseCase getBorrowing;
    @MockitoBean ReportLostUseCase reportLost;
    @MockitoBean ListBorrowingsUseCase listBorrowings;
    @MockitoBean SearchBorrowingsUseCase searchBorrowings;
    @MockitoBean lms.circulation.borrowing.application.ReportFoundUseCase reportFound;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID TENANT = UUID.randomUUID();
    private static final String BODY = "{\"userId\":\"" + UUID.randomUUID() + "\",\"bookCopyIds\":[\"" + UUID.randomUUID() + "\"]}";

    private BorrowingResponse response() {
        return new BorrowingResponse(UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", LocalDate.now(),
                LocalDate.now().plusDays(14), 0, List.of());
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post("/internal/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thieuPermissionBorrowBookBi403() throws Exception {
        mvc.perform(post("/internal/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("permissions", List.of("BorrowingController/returnBook")))
                                .authorities(new SimpleGrantedAuthority("BorrowingController/returnBook"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void duPermissionBorrowBookDuocTao() throws Exception {
        when(borrowBook.execute(any())).thenReturn(response());

        mvc.perform(post("/internal/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("permissions", List.of("BorrowingController/borrow")))
                                .authorities(new SimpleGrantedAuthority("BorrowingController/borrow"))))
                .andExpect(status().isCreated());
    }

    @Test
    void tokenKhongCoTenantBi403TenantRequiredKeCaAdmin() throws Exception {
        mvc.perform(post("/internal/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(jwt().jwt(j -> j.claim("admin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void xemPhieuCuaMinhChiCanDangNhapVaTruyenUserIdTuToken() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(getBorrowing.executeOwn(id, me)).thenReturn(response());

        mvc.perform(get("/internal/borrowings/" + id).with(jwt().jwt(j -> j.subject(me.toString())
                        .claim("tenantId", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(getBorrowing).executeOwn(id, me);
    }

    @Test
    void xemPhieuCuaNguoiKhacTraVe404KhongLo403() throws Exception {
        UUID id = UUID.randomUUID();
        when(getBorrowing.executeOwn(any(), any()))
                .thenThrow(new lms.circulation.borrowing.domain.BorrowingNotFoundException(id));

        mvc.perform(get("/internal/borrowings/" + id).with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())
                        .claim("tenantId", TENANT.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BORROWING_NOT_FOUND"));
    }

    @Test
    void xemPhieuBatKyCanKhoaGetAny() throws Exception {
        UUID id = UUID.randomUUID();
        when(getBorrowing.executeAny(id)).thenReturn(response());

        // chỉ đăng nhập (không khóa) bị 403, kể cả khi có khóa khác
        mvc.perform(get("/internal/borrowings/" + id + "/detail").with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/borrowings/" + id + "/detail").with(tenantUser("BorrowingController/get")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/borrowings/" + id + "/detail").with(tenantUser("BorrowingController/getAny")))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/borrowings/" + id + "/detail")).andExpect(status().isUnauthorized());
        org.mockito.Mockito.verify(getBorrowing, org.mockito.Mockito.never()).executeOwn(any(), any());
    }

    @Test
    void baoMatCanQuyenRiengCuaEndpoint() throws Exception {
        when(reportLost.execute(any())).thenReturn(response());
        String body = "{\"barcode\":\"BC-1\"}";

        mvc.perform(post("/internal/borrowings/losses").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(j -> j.claim("permissions", List.of("BorrowingController/borrow")))
                                .authorities(new SimpleGrantedAuthority("BorrowingController/borrow"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/borrowings/losses").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("permissions", List.of("BorrowingController/reportLost")))
                                .authorities(new SimpleGrantedAuthority("BorrowingController/reportLost"))))
                .andExpect(status().isOk());
    }

    @Test
    void traTheoMaBanSaoCanQuyenReturnCopy() throws Exception {
        when(returnBook.execute(any())).thenReturn(response());
        String body = "{\"barcode\":\"BC-1\",\"condition\":\"DAMAGED\"}";

        mvc.perform(post("/internal/borrowings/returns").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("permissions", List.of("BorrowingController/returnCopy")))
                                .authorities(new SimpleGrantedAuthority("BorrowingController/returnCopy"))))
                .andExpect(status().isOk());
    }

    @Test
    void docGiaXemLuotMuonCuaMinhChiCanDangNhap() throws Exception {
        when(listBorrowings.execute(any())).thenReturn(List.of(response()));

        mvc.perform(get("/internal/borrowings/mine").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminKhongTenantXemTheoDocGiaXuyenTenant() throws Exception {
        UUID readerId = UUID.randomUUID();
        when(listBorrowings.execute(readerId)).thenReturn(List.of(response()));

        mvc.perform(get("/internal/borrowings").param("readerId", readerId.toString())
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/borrowings").param("readerId", readerId.toString())
                        .param("tenantId", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminTenantIdSaiDinhDangBi400() throws Exception {
        mvc.perform(get("/internal/borrowings").param("readerId", UUID.randomUUID().toString())
                        .param("tenantId", "khong-phai-uuid")
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }

    @Test
    void nguoiKhacKhongTenantVanBi403TenantRequiredDuGuiTenantId() throws Exception {
        for (var token : List.of(jwt(), jwt().jwt(j -> j.claim("admin", true)))) {
            mvc.perform(get("/internal/borrowings").param("readerId", UUID.randomUUID().toString())
                            .param("tenantId", UUID.randomUUID().toString()).with(token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    @Test
    void adminTenantGuiTenantIdBiBoQuaVaPhaiCoTenantTrongToken() throws Exception {
        UUID readerId = UUID.randomUUID();
        when(listBorrowings.execute(readerId)).thenReturn(List.of(response()));

        mvc.perform(get("/internal/borrowings").param("readerId", readerId.toString())
                        .param("tenantId", "khong-phai-uuid")
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("admin", true))))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminKhongTenantKhongGhiDuoc() throws Exception {
        mvc.perform(post("/internal/borrowings").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void nhomCuaToiKhongMoXuyenTenant() throws Exception {
        // borrowings/mine chỉ cần đăng nhập (không gắn @CrossTenantReadable), trả theo userId của token
        when(listBorrowings.execute(any())).thenReturn(List.of(response()));
        mvc.perform(get("/internal/borrowings/mine").param("tenantId", "khong-phai-uuid")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void thuThuXemTheoDocGiaCanQuyen() throws Exception {
        mvc.perform(get("/internal/borrowings").param("readerId", UUID.randomUUID().toString()).with(jwt()))
                .andExpect(status().isForbidden());
    }

    // ---------- GET /internal/borrowings/search ----------

    private BorrowingPageResponse page() {
        return new BorrowingPageResponse(List.of(response()), 1, 0, 20);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor tenantUser(String... keys) {
        return jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("permissions", List.of(keys)))
                .authorities(java.util.Arrays.stream(keys).map(SimpleGrantedAuthority::new)
                        .toArray(SimpleGrantedAuthority[]::new));
    }

    @Test
    void searchKhongTokenBi401() throws Exception {
        mvc.perform(get("/internal/borrowings/search")).andExpect(status().isUnauthorized());
    }

    @Test
    void searchThieuKhoaBi403() throws Exception {
        mvc.perform(get("/internal/borrowings/search").with(tenantUser("BorrowingController/byReader")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/borrowings/search").with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchCoKhoaTraJsonDungHinhDang() throws Exception {
        when(searchBorrowings.execute(any())).thenReturn(page());

        mvc.perform(get("/internal/borrowings/search").param("status", "ACTIVE").param("overdue", "true")
                        .param("page", "0").param("size", "20").with(tenantUser("BorrowingController/search")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].items").isArray())
                .andExpect(jsonPath("$.items[0].fines").isArray())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
        org.mockito.Mockito.verify(searchBorrowings).execute(
                new lms.circulation.borrowing.application.SearchBorrowingsQuery("ACTIVE", true, null, 0, 20));
    }

    @Test
    void searchAdminTenantDuocKhongCanKhoa() throws Exception {
        when(searchBorrowings.execute(any())).thenReturn(page());

        mvc.perform(get("/internal/borrowings/search")
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("admin", true))))
                .andExpect(status().isOk());
    }

    @Test
    void searchDuongDanCoDinhKhongBiGetTheoIdBatNham() throws Exception {
        when(searchBorrowings.execute(any())).thenReturn(page());

        mvc.perform(get("/internal/borrowings/search").with(tenantUser("BorrowingController/search")))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(getBorrowing, org.mockito.Mockito.never()).executeOwn(any(), any());
        org.mockito.Mockito.verify(getBorrowing, org.mockito.Mockito.never()).executeAny(any());
    }

    @Test
    void searchThamSoSaiBi400() throws Exception {
        when(searchBorrowings.execute(any())).thenThrow(
                new lms.circulation.borrowing.domain.InvalidBorrowingQueryException("status phải là ACTIVE hoặc RETURNED"));

        mvc.perform(get("/internal/borrowings/search").param("status", "XYZ").with(tenantUser("BorrowingController/search")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void searchThamSoSaiKieuBi400() throws Exception {
        mvc.perform(get("/internal/borrowings/search").param("page", "abc").with(tenantUser("BorrowingController/search")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/borrowings/search").param("readerId", "khong-phai-uuid")
                        .with(tenantUser("BorrowingController/search")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchKhongTenantBi403TenantRequiredTruPlatformAdmin() throws Exception {
        for (var token : List.of(jwt(), jwt().jwt(j -> j.claim("admin", true)))) {
            mvc.perform(get("/internal/borrowings/search").with(token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    @Test
    void searchPlatformAdminKhongTenantXemDuocCoVaKhongTenantId() throws Exception {
        when(searchBorrowings.execute(any())).thenReturn(page());

        mvc.perform(get("/internal/borrowings/search").with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/borrowings/search").param("tenantId", UUID.randomUUID().toString())
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/borrowings/search").param("tenantId", "khong-phai-uuid")
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }
}
