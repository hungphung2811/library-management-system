package lms.catalog.reporting.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.catalog.config.SecurityConfiguration;
import lms.catalog.reporting.application.CatalogSummaryResponse;
import lms.catalog.reporting.application.GetCatalogSummaryUseCase;
import lms.catalog.reporting.infrastructure.web.CatalogStatsController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** WebMvc cho CatalogStatsController: khóa CatalogStatsController/summary, tenant bắt buộc, module lõi. */
@WebMvcTest(CatalogStatsController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class CatalogStatsControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String URL = "/internal/reporting/catalog/summary";

    @Autowired MockMvc mvc;

    @MockitoBean GetCatalogSummaryUseCase summary;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor withKey(String key, Object modules) {
        return jwt().jwt(j -> {
            j.claim("tenantId", TENANT.toString()).claim("permissions", List.of(key));
            if (modules != null) j.claim("modules", modules);
        }).authorities(new SimpleGrantedAuthority(key));
    }

    private static CatalogSummaryResponse sample() {
        return new CatalogSummaryResponse(62, new CatalogSummaryResponse.Copies(150,
                Map.of("AVAILABLE", 100L, "LOANED", 50L)));
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        verifyNoInteractions(summary);
    }

    @Test
    void khoaChuaGanBi403() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(summary);
    }

    @Test
    void khoaCuaEndpointKhacKhongDuQuyen() throws Exception {
        mvc.perform(get(URL).with(withKey("TitleController/search", null))).andExpect(status().isForbidden());
        verifyNoInteractions(summary);
    }

    @Test
    void tokenKhongCoTenantBi403KeCaAdmin() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(j -> j.claim("admin", true))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(summary);
    }

    @Test
    void coKhoaTra200VaHopDong() throws Exception {
        when(summary.execute()).thenReturn(sample());

        mvc.perform(get(URL).with(withKey("CatalogStatsController/summary", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titles").value(62))
                .andExpect(jsonPath("$.copies.total").value(150))
                .andExpect(jsonPath("$.copies.byStatus.AVAILABLE").value(100));
        verify(summary).execute();
    }

    @Test
    void moduleLoiNenTenantChiCoSalesVanXemDuoc() throws Exception {
        when(summary.execute()).thenReturn(sample());

        mvc.perform(get(URL).with(withKey("CatalogStatsController/summary", List.of("SALES"))))
                .andExpect(status().isOk());
    }

    @Test
    void adminCoTenantBoQuaKiemKhoa() throws Exception {
        when(summary.execute()).thenReturn(sample());

        mvc.perform(get(URL).with(jwt().jwt(j -> j.claim("tenantId", TENANT.toString()).claim("admin", true))))
                .andExpect(status().isOk());
    }
}
