package lms.support.audit.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Kiểm chứng RBAC của support-service: 401 không token, 403 thiếu AUDIT_VIEW, 200 đủ quyền, denyAll mặc định. */
@WebMvcTest(AuditLogController.class)
@Import({SecurityConfiguration.class, AuditExceptionHandler.class})
@TestPropertySource(properties = "app.security.enabled=true")
class AuditLogSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean QueryAuditLogsUseCase query;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void thieuQuyenAuditViewBi403() throws Exception {
        mvc.perform(get("/api/audit-logs").with(jwt().authorities(new SimpleGrantedAuthority("BOOK_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void duQuyenAuditViewDuocXem() throws Exception {
        when(query.execute(any())).thenReturn(new PageResponse<>(List.of(), 0, 0, 20));

        mvc.perform(get("/api/audit-logs").param("aggregateId", "x")
                        .with(jwt().authorities(new SimpleGrantedAuthority("AUDIT_VIEW"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void thamSoPhanTrangSaiBi400() throws Exception {
        mvc.perform(get("/api/audit-logs").param("size", "1000")
                        .with(jwt().authorities(new SimpleGrantedAuthority("AUDIT_VIEW"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_AUDIT_QUERY"));
    }

    @Test
    void duongDanKhongKhaiBaoBiDenyAllKeCaKhiCoToken() throws Exception {
        mvc.perform(get("/api/khong-ton-tai").with(jwt().authorities(new SimpleGrantedAuthority("AUDIT_VIEW"))))
                .andExpect(status().isForbidden());
    }
}
