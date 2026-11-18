package lms.identity.apiclient.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase.DailyUsage;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase.UsageReport;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.infrastructure.web.ApplicationController;
import lms.identity.apiclient.infrastructure.web.ApplicationExceptionHandler;
import lms.identity.apiclient.infrastructure.web.PlatformApplicationController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * GET /internal/applications/{id}/usage (khóa động ApplicationController/usage) và bản platform: tham số ngày, ánh xạ lỗi
 * (404 tenant khác, 400 khoảng sai, 503 chung khi kho bộ đếm lỗi). Không cần Docker.
 */
@WebMvcTest({ApplicationController.class, PlatformApplicationController.class})
@Import({SecurityConfiguration.class, ApplicationExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class ApplicationUsageWebTest {

    @Autowired MockMvc mvc;

    @MockitoBean CreateApplicationUseCase create;
    @MockitoBean QueryApplicationUseCase query;
    @MockitoBean ManageApplicationUseCase manage;
    @MockitoBean ManageCredentialsUseCase credentials;
    @MockitoBean ManageApplicationDomainsUseCase domains;
    @MockitoBean QueryApplicationUsageUseCase usage;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID APP = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.parse("2026-11-01");
    private static final LocalDate TO = LocalDate.parse("2026-11-02");
    private static final UsageReport REPORT = new UsageReport(APP, FROM, TO, 12,
            List.of(new DailyUsage(FROM, 5), new DailyUsage(TO, 7)));

    @BeforeEach
    void bindTenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("admin", true).claim("tenantId", TENANT.toString())
                .claim("modules", List.of("LENDING", "SALES")));
    }

    private RequestPostProcessor withKey(String key) {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT.toString())
                .claim("permissions", List.of(key))).authorities(new SimpleGrantedAuthority(key));
    }

    @Test
    void tenantAdminXemThongKeTheoNgayTenantLayTuToken() throws Exception {
        when(usage.usage(TENANT, APP, FROM, TO)).thenReturn(REPORT);
        mvc.perform(get("/internal/applications/" + APP + "/usage?from=2026-11-01&to=2026-11-02").with(tenantAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(APP.toString()))
                .andExpect(jsonPath("$.totalRequests").value(12))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.days[1].totalRequests").value(7));
        verify(usage).usage(TENANT, APP, FROM, TO);
    }

    @Test
    void thieuThamSoThiTruyenNull() throws Exception {
        when(usage.usage(TENANT, APP, null, null)).thenReturn(REPORT);
        mvc.perform(get("/internal/applications/" + APP + "/usage").with(tenantAdmin())).andExpect(status().isOk());
        verify(usage).usage(TENANT, APP, null, null);
    }

    @Test
    void ngayKhongPhaiIsoLa400KhongGoiUseCase() throws Exception {
        mvc.perform(get("/internal/applications/" + APP + "/usage?from=01/11/2026").with(tenantAdmin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(usage);
    }

    @Test
    void khoaDongApplicationControllerUsageDuQuaKhoaKhacBi403() throws Exception {
        when(usage.usage(any(), any(), any(), any())).thenReturn(REPORT);
        mvc.perform(get("/internal/applications/" + APP + "/usage").with(withKey("ApplicationController/usage")))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/applications/" + APP + "/usage").with(withKey("ApplicationController/get")))
                .andExpect(status().isForbidden());
    }

    @Test
    void ungDungCuaTenantKhacLa404CungThanVoiKhongTonTai() throws Exception {
        when(usage.usage(any(), any(), any(), any()))
                .thenThrow(new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
        mvc.perform(get("/internal/applications/" + APP + "/usage").with(tenantAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy ứng dụng"));
    }

    @Test
    void khoangQuaDaiLa400() throws Exception {
        when(usage.usage(any(), any(), any(), any()))
                .thenThrow(new ApplicationException(Reason.INVALID_APPLICATION, "Khoảng thống kê tối đa 31 ngày"));
        mvc.perform(get("/internal/applications/" + APP + "/usage?from=2026-01-01&to=2026-12-31").with(tenantAdmin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_APPLICATION"));
    }

    @Test
    void khoBoDemLoiLa503Chung() throws Exception {
        when(usage.usage(any(), any(), any(), any()))
                .thenThrow(new ApplicationException(Reason.USAGE_UNAVAILABLE, "Thống kê tạm thời không khả dụng"));
        mvc.perform(get("/internal/applications/" + APP + "/usage").with(tenantAdmin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("USAGE_UNAVAILABLE"));
    }

    @Test
    void platformAdminXemQuaDuongDanTenant() throws Exception {
        when(usage.usage(TENANT, APP, FROM, TO)).thenReturn(REPORT);
        RequestPostProcessor platform = jwt().jwt(j -> j.subject(USER.toString()).claim("platformAdmin", true));
        mvc.perform(get("/api/platform/tenants/" + TENANT + "/applications/" + APP + "/usage?from=2026-11-01&to=2026-11-02")
                        .with(platform))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalRequests").value(12));
        mvc.perform(get("/api/platform/tenants/" + TENANT + "/applications/" + APP + "/usage").with(tenantAdmin()))
                .andExpect(status().isForbidden());
    }
}
