package lms.identity.contract.integration;

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
import lms.identity.config.SecurityConfiguration;
import lms.identity.contract.application.PlatformOverviewUseCase;
import lms.identity.contract.application.PlatformOverviewUseCase.PlatformOverviewResponse;
import lms.identity.contract.application.PlatformOverviewUseCase.TenantCounts;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.infrastructure.web.ContractExceptionHandler;
import lms.identity.contract.infrastructure.web.PlatformDashboardController;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** GET /api/platform/dashboard/overview (ADR-0018): chỉ platformAdmin. Bật lại kiểm khóa; không cần Docker. */
@WebMvcTest(PlatformDashboardController.class)
@Import({SecurityConfiguration.class, ContractExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class PlatformDashboardSecurityTest {

    private static final String URL = "/api/platform/dashboard/overview";
    private static final UUID TENANT = UUID.randomUUID();

    @Autowired MockMvc mvc;

    @MockitoBean PlatformOverviewUseCase overview;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    private RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                .claim("tenantId", TENANT.toString()).claim("modules", List.of("LENDING"))
                .claim("permissions", List.of("PlatformDashboardController/overview")));
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void quanTriVienTenantBi403VaKhongGoiUseCase() throws Exception {
        mvc.perform(get(URL).with(tenantAdmin())).andExpect(status().isForbidden());
        verifyNoInteractions(overview);
    }

    @Test
    void platformAdminFalseVanBi403() throws Exception {
        mvc.perform(get(URL).with(jwt().jwt(j -> j.claim("platformAdmin", false))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(overview);
    }

    @Test
    void platformAdminKhongCanTenantIdXemDuoc() throws Exception {
        when(overview.execute(20)).thenReturn(new PlatformOverviewResponse(new TenantCounts(3, 2, 1),
                Map.of("LENDING", 2L, "SALES", 1L), 20, 0, List.of()));
        mvc.perform(get(URL).param("withinDays", "20").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants.total").value(3))
                .andExpect(jsonPath("$.tenants.suspended").value(1))
                .andExpect(jsonPath("$.activeTenantsByModule.SALES").value(1))
                .andExpect(jsonPath("$.withinDays").value(20))
                .andExpect(jsonPath("$.expiringContractCount").value(0));
        verify(overview).execute(20);
    }

    @Test
    void withinDaysKhongHopLeTraLoiNghiepVu() throws Exception {
        when(overview.execute(0)).thenThrow(
                new ContractException(ContractException.Reason.INVALID_CONTRACT, "withinDays phải từ 1 đến 365"));
        mvc.perform(get(URL).param("withinDays", "0").with(platformAdmin()))
                .andExpect(status().is4xxClientError());
    }
}
