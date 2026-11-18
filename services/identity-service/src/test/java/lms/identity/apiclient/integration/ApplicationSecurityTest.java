package lms.identity.apiclient.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.ApplicationViews.CreatedCredential;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * API Application (SaaS API Authentication P1): khóa động ApplicationController/* (ADR-0003), PlatformApplicationController
 * chỉ platformAdmin, secret chỉ ở phản hồi tạo credential kèm no-store, ánh xạ lỗi, 404 cùng thân với tenant khác (T-P2 ở
 * mức web). Bật lại kiểm khóa bằng @TestPropertySource, mock PermissionKeyRegistrar. Không cần Docker. TenantContext do
 * TenantContextFilter đặt ở runtime (không nằm trong slice WebMvc) nên test đặt tay.
 */
@WebMvcTest({ApplicationController.class, PlatformApplicationController.class})
@Import({SecurityConfiguration.class, ApplicationExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class ApplicationSecurityTest {

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
    private static final UUID CRED = UUID.randomUUID();
    private static final String BASE = "/internal/applications";
    private static final String PLATFORM = "/api/platform/tenants/" + TENANT + "/applications";
    private static final Instant AT = Instant.parse("2026-11-18T01:00:00Z");
    private static final ApplicationDetail DETAIL = new ApplicationDetail(APP, APP, "ERP", "INTEGRATION", "ACTIVE",
            null, 600, List.of("books:read"), List.of(), AT, USER, AT, USER);

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

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("platformAdmin", true));
    }

    private RequestPostProcessor withKey(String key) {
        return jwt().jwt(j -> j.subject(USER.toString()).claim("tenantId", TENANT.toString())
                .claim("permissions", List.of(key))).authorities(new SimpleGrantedAuthority(key));
    }

    private List<MockHttpServletRequestBuilder> tenantEndpoints() {
        MediaType json = MediaType.APPLICATION_JSON;
        return List.of(get(BASE), get(BASE + "/" + APP),
                post(BASE).contentType(json).content("{\"name\":\"ERP\",\"kind\":\"INTEGRATION\"}"),
                put(BASE + "/" + APP).contentType(json).content("{\"name\":\"ERP\"}"), delete(BASE + "/" + APP),
                put(BASE + "/" + APP + "/scopes").contentType(json).content("{\"scopes\":[\"books:read\"]}"),
                post(BASE + "/" + APP + "/credentials"),
                post(BASE + "/" + APP + "/credentials/" + CRED + "/revocation"),
                post(BASE + "/" + APP + "/site-key/rotation"), get(BASE + "/" + APP + "/domains"),
                post(BASE + "/" + APP + "/domains").contentType(json).content("{\"origin\":\"https://a.vn\"}"),
                delete(BASE + "/" + APP + "/domains/" + CRED), get(BASE + "/" + APP + "/history"),
                get(BASE + "/" + APP + "/usage"));
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (MockHttpServletRequestBuilder r : tenantEndpoints()) mvc.perform(r).andExpect(status().isUnauthorized());
        mvc.perform(get(PLATFORM)).andExpect(status().isUnauthorized());
    }

    @Test
    void nguoiThuongThieuKhoaBi403VaKhongCoTenantIdBi403TenantRequired() throws Exception {
        for (MockHttpServletRequestBuilder r : tenantEndpoints()) {
            mvc.perform(r.with(withKey("OtherController/other"))).andExpect(status().isForbidden());
        }
        RequestPostProcessor noTenant = jwt().jwt(j -> j.subject(USER.toString()).claim("admin", true));
        mvc.perform(get(BASE).with(noTenant)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        verifyNoInteractions(create, query, manage, credentials, domains);
    }

    @Test
    void khoaDongGanChoRoleMoiDuocQuaVaKhoaKhacKhongDuQua() throws Exception {
        when(query.list(TENANT)).thenReturn(List.of());
        mvc.perform(get(BASE).with(withKey("ApplicationController/list"))).andExpect(status().isOk());
        // khóa của method khác không đủ
        mvc.perform(get(BASE).with(withKey("ApplicationController/get"))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + APP + "/credentials").with(withKey("ApplicationController/list")))
                .andExpect(status().isForbidden());
        // khóa đã chốt cho tenant admin tự phục vụ (đổi tên method đổi khóa: báo lại nếu phải đổi)
        verify(query).list(TENANT);
    }

    @Test
    void tenantAdminTaoUngDungLayTenantTuTokenKhongTuBody() throws Exception {
        UUID other = UUID.randomUUID();
        when(create.execute(eq(TENANT), any(), eq(USER))).thenReturn(DETAIL);

        mvc.perform(post(BASE).with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ERP\",\"kind\":\"INTEGRATION\",\"rateLimitPerMinute\":600,"
                                + "\"tenantId\":\"" + other + "\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/applications/" + APP))
                .andExpect(jsonPath("$.id").value(APP.toString())).andExpect(jsonPath("$.kind").value("INTEGRATION"));
        verify(create).execute(eq(TENANT), any(), eq(USER));
    }

    @Test
    void taoCredentialTraSecretMotLanKemNoStoreVaGoiTiepKhongCoSecret() throws Exception {
        String secret = "lms_sk_" + "A".repeat(43);
        when(credentials.create(TENANT, APP, null, USER)).thenReturn(new CreatedCredential(CRED, APP, secret,
                "AAAA", AT, AT.plusSeconds(86400)));

        mvc.perform(post(BASE + "/" + APP + "/credentials").with(tenantAdmin()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.clientSecret").value(secret))
                .andExpect(jsonPath("$.clientId").value(APP.toString()))
                .andExpect(jsonPath("$.secretHint").value("AAAA"));

        when(query.get(TENANT, APP)).thenReturn(DETAIL);
        String json = mvc.perform(get(BASE + "/" + APP).with(tenantAdmin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("clientSecret").doesNotContain("secretHash");
    }

    @Test
    void bodyCredentialCoHanTuyChon() throws Exception {
        when(credentials.create(TENANT, APP, 30, USER)).thenReturn(new CreatedCredential(CRED, APP, "lms_sk_x",
                "x", AT, AT));
        mvc.perform(post(BASE + "/" + APP + "/credentials").with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\":30}")).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/" + APP + "/credentials").with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expiresInDays\":0}")).andExpect(status().isBadRequest());
    }

    @Test
    void bodySaiLa400KhongGoiUseCase() throws Exception {
        MediaType json = MediaType.APPLICATION_JSON;
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(json).content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(json).content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/" + APP + "/scopes").with(tenantAdmin()).contentType(json).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/" + APP + "/domains").with(tenantAdmin()).contentType(json).content("{\"origin\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(create, manage, domains);
    }

    @Test
    void anhXaMaLoiVaTenantKhacCungThanVoiKhongTonTai() throws Exception {
        when(query.get(TENANT, APP)).thenThrow(new ApplicationException(Reason.APPLICATION_NOT_FOUND, "Không tìm thấy ứng dụng"));
        when(manage.replaceScopes(eq(TENANT), eq(APP), any(), any()))
                .thenThrow(new ApplicationException(Reason.SCOPE_NOT_GRANTABLE, "chưa cấp"));
        when(credentials.create(TENANT, APP, null, USER))
                .thenThrow(new ApplicationException(Reason.CREDENTIAL_LIMIT_EXCEEDED, "đủ 2"));
        when(create.execute(eq(TENANT), any(), any()))
                .thenThrow(new ApplicationException(Reason.CONTRACT_REQUIRED, "cần hợp đồng"));

        mvc.perform(get(BASE + "/" + APP).with(tenantAdmin())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        mvc.perform(put(BASE + "/" + APP + "/scopes").with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopes\":[\"books:write\"]}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCOPE_NOT_GRANTABLE"));
        mvc.perform(post(BASE + "/" + APP + "/credentials").with(tenantAdmin())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_LIMIT_EXCEEDED"));
        mvc.perform(post(BASE).with(tenantAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ERP\",\"kind\":\"INTEGRATION\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_REQUIRED"));
    }

    // ---- platform ----

    @Test
    void platformChiChoPlatformAdminKhongDungKhoa() throws Exception {
        List<MockHttpServletRequestBuilder> endpoints = List.of(get(PLATFORM), get(PLATFORM + "/" + APP),
                get(PLATFORM + "/" + APP + "/history"), get(PLATFORM + "/" + APP + "/usage"), post(PLATFORM + "/" + APP + "/suspension"),
                post(PLATFORM + "/" + APP + "/reactivation"), delete(PLATFORM + "/" + APP));
        for (MockHttpServletRequestBuilder r : endpoints) {
            mvc.perform(r.with(tenantAdmin())).andExpect(status().isForbidden());
            mvc.perform(r.with(withKey("PlatformApplicationController/list"))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(query, manage);
    }

    @Test
    void platformAdminXemTamNgungKichHoatVaThuHoi() throws Exception {
        when(query.list(TENANT)).thenReturn(List.of());
        when(manage.suspend(TENANT, APP, USER)).thenReturn(DETAIL);
        when(manage.reactivate(TENANT, APP, USER)).thenReturn(DETAIL);

        mvc.perform(get(PLATFORM).with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(post(PLATFORM + "/" + APP + "/suspension").with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(post(PLATFORM + "/" + APP + "/reactivation").with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(delete(PLATFORM + "/" + APP).with(platformAdmin())).andExpect(status().isNoContent());
        verify(manage).delete(TENANT, APP, USER);
    }
}
