package lms.identity.tenant.integration;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.tenant.application.AddTenantAdminUseCase;
import lms.identity.tenant.application.AttachUserToTenantUseCase;
import lms.identity.tenant.application.CreateTenantRequest;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.DisableTenantModuleUseCase;
import lms.identity.tenant.application.EnableTenantModuleUseCase;
import lms.identity.tenant.application.GetTenantUseCase;
import lms.identity.tenant.application.ListTenantModulesUseCase;
import lms.identity.tenant.application.RemoveTenantAdminUseCase;
import lms.identity.tenant.application.SearchTenantsUseCase;
import lms.identity.tenant.application.SetTenantStatusUseCase;
import lms.identity.tenant.application.TenantModuleResponse;
import lms.identity.tenant.application.TenantResponse;
import lms.identity.tenant.application.UpdateTenantUseCase;
import lms.identity.tenant.domain.InvalidTenantModulesException;
import lms.identity.tenant.domain.TenantModuleRequiredException;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantStatus;
import lms.identity.tenant.domain.UnknownModuleException;
import lms.identity.tenant.infrastructure.web.PlatformTenantController;
import lms.identity.tenant.infrastructure.web.PlatformTenantModuleController;
import lms.identity.tenant.infrastructure.web.TenantExceptionHandler;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * /api/platform/tenants/{id}/modules (ADR-0009) chỉ dành cho platformAdmin; ánh xạ lỗi domain sang HTTP; body tạo
 * tenant nhận {@code modules}. Không cần Docker.
 */
@WebMvcTest({PlatformTenantModuleController.class, PlatformTenantController.class})
@Import({SecurityConfiguration.class, TenantExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class PlatformTenantModuleSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean ListTenantModulesUseCase list;
    @MockitoBean EnableTenantModuleUseCase enable;
    @MockitoBean DisableTenantModuleUseCase disable;
    @MockitoBean CreateTenantUseCase create;
    @MockitoBean UpdateTenantUseCase update;
    @MockitoBean SetTenantStatusUseCase status;
    @MockitoBean SearchTenantsUseCase search;
    @MockitoBean GetTenantUseCase getTenant;
    @MockitoBean AddTenantAdminUseCase addAdmin;
    @MockitoBean RemoveTenantAdminUseCase removeAdmin;
    @MockitoBean AttachUserToTenantUseCase attach;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String BASE = "/api/platform/tenants/" + TENANT + "/modules";
    private static final TenantModuleResponse SALES_ON =
            new TenantModuleResponse("SALES", true, Instant.parse("2026-11-16T01:00:00Z"), OPERATOR);

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(OPERATOR.toString()).claim("platformAdmin", true));
    }

    /** Quản trị viên của tenant: admin=true, có tenantId, có cả khóa Controller/method; không phải platform admin. */
    private RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                .claim("tenantId", TENANT.toString()).claim("modules", List.of("LENDING", "SALES"))
                .claim("permissions", List.of("PlatformTenantModuleController/enable")));
    }

    private List<MockHttpServletRequestBuilder> endpoints() {
        return List.of(get(BASE), put(BASE + "/SALES"), delete(BASE + "/SALES"));
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (RequestBuilder request : endpoints()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void quanTriVienTenantBi403VaKhongGoiUseCase() throws Exception {
        for (MockHttpServletRequestBuilder request : endpoints()) {
            mvc.perform(request.with(tenantAdmin())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(list, enable, disable);
    }

    @Test
    void platformAdminFalseVanBi403() throws Exception {
        mvc.perform(get(BASE).with(jwt().jwt(j -> j.claim("platformAdmin", false))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(list);
    }

    @Test
    void platformAdminKhongCanTenantIdVaKhongCanClaimModulesXemBatTat() throws Exception {
        when(list.execute(TENANT)).thenReturn(List.of(
                new TenantModuleResponse("LENDING", true, Instant.parse("2026-11-01T00:00:00Z"), null),
                new TenantModuleResponse("SALES", false, null, null)));
        when(enable.execute(TENANT, "SALES", OPERATOR)).thenReturn(SALES_ON);
        when(disable.execute(TENANT, "SALES", OPERATOR))
                .thenReturn(new TenantModuleResponse("SALES", false, Instant.parse("2026-11-16T02:00:00Z"), OPERATOR));

        mvc.perform(get(BASE).with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].module").value("LENDING"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].module").value("SALES"))
                .andExpect(jsonPath("$[1].enabled").value(false))
                .andExpect(jsonPath("$[1].updatedAt").doesNotExist());
        mvc.perform(put(BASE + "/SALES").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.module").value("SALES"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.updatedBy").value(OPERATOR.toString()));
        mvc.perform(delete(BASE + "/SALES").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        verify(enable).execute(TENANT, "SALES", OPERATOR);
        verify(disable).execute(TENANT, "SALES", OPERATOR);
    }

    @Test
    void tatModuleCuoiTra409TenantModuleRequired() throws Exception {
        when(disable.execute(TENANT, "LENDING", OPERATOR))
                .thenThrow(new TenantModuleRequiredException("phải còn ít nhất một module"));

        mvc.perform(delete(BASE + "/LENDING").with(platformAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_MODULE_REQUIRED"));
    }

    @Test
    void maModuleSaiTra400UnknownModule() throws Exception {
        when(enable.execute(TENANT, "PAYROLL", OPERATOR)).thenThrow(new UnknownModuleException("Module không tồn tại"));

        mvc.perform(put(BASE + "/PAYROLL").with(platformAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_MODULE"));
    }

    @Test
    void tenantKhongTonTaiTra404TenantNotFound() throws Exception {
        when(list.execute(TENANT)).thenThrow(new TenantNotFoundException("Không tìm thấy tenant"));

        mvc.perform(get(BASE).with(platformAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
    }

    @Test
    void taoTenantNhanModulesVaChuyenXuongUseCase() throws Exception {
        when(create.execute(any(), eq(OPERATOR))).thenReturn(new TenantResponse(TENANT, "shop-a", "Hiệu sách A", null,
                TenantStatus.ACTIVE, 1, List.of("SALES")));
        String body = "{\"code\":\"shop-a\",\"name\":\"Hiệu sách A\",\"modules\":[\"SALES\"],\"newAdmin\":"
                + "{\"username\":\"shop-admin\",\"fullName\":\"Quản trị\",\"email\":\"a@shop.vn\","
                + "\"temporaryPassword\":\"Temp@12345\"}}";

        mvc.perform(post("/api/platform/tenants").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(platformAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modules[0]").value("SALES"));

        ArgumentCaptor<CreateTenantRequest> captor = ArgumentCaptor.forClass(CreateTenantRequest.class);
        verify(create).execute(captor.capture(), eq(OPERATOR));
        assertThat(captor.getValue().modules()).containsExactly("SALES");
    }

    @Test
    void taoTenantKhongNeuModulesChuyenNullDeUseCaseMacDinhLending() throws Exception {
        when(create.execute(any(), eq(OPERATOR))).thenReturn(new TenantResponse(TENANT, "lib-a", "Thư viện A", null,
                TenantStatus.ACTIVE, 1, List.of("LENDING")));
        String body = "{\"code\":\"lib-a\",\"name\":\"Thư viện A\",\"newAdmin\":"
                + "{\"username\":\"lib-admin\",\"fullName\":\"Quản trị\",\"email\":\"a@lib.vn\","
                + "\"temporaryPassword\":\"Temp@12345\"}}";

        mvc.perform(post("/api/platform/tenants").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(platformAdmin()))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateTenantRequest> captor = ArgumentCaptor.forClass(CreateTenantRequest.class);
        verify(create).execute(captor.capture(), eq(OPERATOR));
        assertThat(captor.getValue().modules()).isNull();
    }

    @Test
    void taoTenantModulesRongTra400InvalidModules() throws Exception {
        when(create.execute(any(), eq(OPERATOR)))
                .thenThrow(new InvalidTenantModulesException("Tenant phải có ít nhất một module"));
        String body = "{\"code\":\"lib-a\",\"name\":\"Thư viện A\",\"modules\":[],\"newAdmin\":"
                + "{\"username\":\"lib-admin\",\"fullName\":\"Quản trị\",\"email\":\"a@lib.vn\","
                + "\"temporaryPassword\":\"Temp@12345\"}}";

        mvc.perform(post("/api/platform/tenants").contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(platformAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MODULES"));
    }
}
