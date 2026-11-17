package lms.support.audit.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.util.List;
import lms.support.audit.application.PageResponse;
import lms.support.audit.application.QueryAuditLogsUseCase;
import lms.support.audit.infrastructure.web.AuditExceptionHandler;
import lms.support.audit.infrastructure.web.AuditLogController;
import lms.support.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm chứng RBAC của support-service: 401 không token, 403 thiếu khóa AuditLogController/search, 200 đủ quyền, denyAll mặc định. */
@WebMvcTest(AuditLogController.class)
@Import({SecurityConfiguration.class, AuditExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class AuditLogSecurityTest {

    private static final String TENANT = "00000000-0000-0000-0000-0000000000a1";

    @Autowired MockMvc mvc;

    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @MockitoBean QueryAuditLogsUseCase query;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/internal/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenKhongCoTenantBi403TenantRequiredKeCaKhiDuQuyen() throws Exception {
        mvc.perform(get("/internal/audit-logs")
                        .with(jwt().authorities(new SimpleGrantedAuthority("AuditLogController/search"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void thieuKhoaQuyenBi403() throws Exception {
        mvc.perform(get("/internal/audit-logs").with(jwt().jwt(j -> j.claim("tenantId", TENANT))
                        .authorities(new SimpleGrantedAuthority("BOOK_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void duKhoaQuyenDuocXem() throws Exception {
        when(query.execute(any())).thenReturn(new PageResponse<>(List.of(), 0, 0, 20));

        mvc.perform(get("/internal/audit-logs").param("aggregateId", "x")
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT))
                                .authorities(new SimpleGrantedAuthority("AuditLogController/search"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void thamSoPhanTrangSaiBi400() throws Exception {
        mvc.perform(get("/internal/audit-logs").param("size", "1000")
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT)).authorities(new SimpleGrantedAuthority("AuditLogController/search"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUDIT_QUERY"));
    }

    @Test
    void duongDanNgoaiApiBiDenyAllKeCaKhiCoToken() throws Exception {
        mvc.perform(get("/khong-ton-tai").with(jwt().authorities(new SimpleGrantedAuthority("AuditLogController/search"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminKhongTenantDuocXemXuyenTenantKhongCanKhoa() throws Exception {
        when(query.execute(any())).thenReturn(new PageResponse<>(List.of(), 0, 0, 20));

        mvc.perform(get("/internal/audit-logs").with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/internal/audit-logs").param("tenantId", TENANT)
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminTenantIdSaiDinhDangBi400() throws Exception {
        mvc.perform(get("/internal/audit-logs").param("tenantId", "xyz")
                        .with(jwt().jwt(j -> j.claim("platformAdmin", true))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }

    @Test
    void nguoiThuongKhongTenantVanBi403DuGuiTenantId() throws Exception {
        mvc.perform(get("/internal/audit-logs").param("tenantId", TENANT)
                        .with(jwt().authorities(new SimpleGrantedAuthority("AuditLogController/search"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void nguoiThuongBoQuaTenantIdSaiDinhDang() throws Exception {
        when(query.execute(any())).thenReturn(new PageResponse<>(List.of(), 0, 0, 20));

        mvc.perform(get("/internal/audit-logs").param("tenantId", "xyz")
                        .with(jwt().jwt(j -> j.claim("tenantId", TENANT))
                                .authorities(new SimpleGrantedAuthority("AuditLogController/search"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminDuocBoQuaKiemTraKhoaQuyen() throws Exception {
        when(query.execute(any())).thenReturn(new PageResponse<>(List.of(), 0, 0, 20));

        mvc.perform(get("/internal/audit-logs").with(jwt().jwt(j -> j.claim("admin", true).claim("tenantId", TENANT))))
                .andExpect(status().isOk());
    }
}
