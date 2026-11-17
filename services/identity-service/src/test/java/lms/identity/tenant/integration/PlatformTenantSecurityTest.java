package lms.identity.tenant.integration;

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

import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.tenant.application.AddTenantAdminUseCase;
import lms.identity.tenant.application.AttachUserToTenantUseCase;
import lms.identity.tenant.application.CreateTenantRequest;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.GetTenantUseCase;
import lms.identity.tenant.application.ManageOrganizationUseCase;
import lms.identity.tenant.application.OrganizationResponse;
import lms.identity.tenant.application.PageResponse;
import lms.identity.tenant.application.RemoveTenantAdminUseCase;
import lms.identity.tenant.application.SearchTenantsUseCase;
import lms.identity.tenant.application.SetTenantStatusUseCase;
import lms.identity.tenant.application.TenantDetailResponse;
import lms.identity.tenant.application.TenantResponse;
import lms.identity.tenant.application.TenantSearchRequest;
import lms.identity.tenant.application.UpdateTenantUseCase;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.TenantStatus;
import lms.identity.tenant.infrastructure.web.PlatformOrganizationController;
import lms.identity.tenant.infrastructure.web.PlatformTenantController;
import lms.identity.tenant.infrastructure.web.TenantExceptionHandler;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
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
 * /api/platform/** chỉ dành cho token có claim platformAdmin=true (spec §7): quản trị viên tenant (admin=true, có
 * tenantId, mọi permission) và người dùng thường đều 403; không token 401. Không cần Docker.
 */
@WebMvcTest({PlatformTenantController.class, PlatformOrganizationController.class})
@Import({SecurityConfiguration.class, TenantExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class PlatformTenantSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean CreateTenantUseCase create;
    @MockitoBean UpdateTenantUseCase update;
    @MockitoBean SetTenantStatusUseCase status;
    @MockitoBean SearchTenantsUseCase search;
    @MockitoBean GetTenantUseCase getTenant;
    @MockitoBean AddTenantAdminUseCase addAdmin;
    @MockitoBean RemoveTenantAdminUseCase removeAdmin;
    @MockitoBean AttachUserToTenantUseCase attach;
    @MockitoBean ManageOrganizationUseCase organizations;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID OPERATOR = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final String TENANT_ID = TENANT.toString();

    private static final String CREATE_BODY = "{\"code\":\"lib-a\",\"name\":\"Thư viện A\",\"newAdmin\":"
            + "{\"username\":\"lib-a-admin\",\"fullName\":\"Quản trị A\",\"email\":\"a@lib.vn\","
            + "\"temporaryPassword\":\"Temp@12345\"}}";
    private static final String UPDATE_BODY = "{\"name\":\"Tên mới\"}";
    private static final String ADMIN_BODY = "{\"existingUserId\":\"" + UUID.randomUUID() + "\"}";
    private static final String MEMBER_BODY = "{\"userId\":\"" + UUID.randomUUID() + "\"}";
    private static final String ORG_BODY = "{\"name\":\"Trường A\"}";

    private RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(OPERATOR.toString()).claim("platformAdmin", true));
    }

    /** Quản trị viên của một tenant: admin=true, có tenantId, kể cả có sẵn khóa Controller/method. */
    private RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true).claim("tenantId", TENANT_ID)
                .claim("permissions", List.of("PlatformTenantController/create", "PlatformTenantController/search")));
    }

    private RequestPostProcessor ordinaryUser() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT_ID)
                .claim("permissions", List.of("BorrowingController/borrow")));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private List<MockHttpServletRequestBuilder> allEndpoints() {
        UUID org = UUID.randomUUID();
        return List.of(
                json(post("/api/platform/tenants"), CREATE_BODY),
                get("/api/platform/tenants").param("keyword", "lib").param("status", "ACTIVE"),
                get("/api/platform/tenants/" + TENANT),
                json(put("/api/platform/tenants/" + TENANT), UPDATE_BODY),
                post("/api/platform/tenants/" + TENANT + "/suspend"),
                post("/api/platform/tenants/" + TENANT + "/activate"),
                json(post("/api/platform/tenants/" + TENANT + "/admins"), ADMIN_BODY),
                delete("/api/platform/tenants/" + TENANT + "/admins/" + UUID.randomUUID()),
                json(post("/api/platform/tenants/" + TENANT + "/members"), MEMBER_BODY),
                json(post("/api/platform/organizations"), ORG_BODY),
                get("/api/platform/organizations"),
                get("/api/platform/organizations/" + org),
                json(put("/api/platform/organizations/" + org), ORG_BODY));
    }

    @Test
    void khongTokenBi401() throws Exception {
        for (RequestBuilder request : allEndpoints()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void quanTriVienTenantBi403ChoMoiEndpointNenTang() throws Exception {
        for (MockHttpServletRequestBuilder request : allEndpoints()) {
            mvc.perform(request.with(tenantAdmin())).andExpect(status().isForbidden());
        }
        verifyNoUseCaseCalled();
    }

    @Test
    void nguoiDungThuongBi403ChoMoiEndpointNenTang() throws Exception {
        for (MockHttpServletRequestBuilder request : allEndpoints()) {
            mvc.perform(request.with(ordinaryUser())).andExpect(status().isForbidden());
        }
        verifyNoUseCaseCalled();
    }

    @Test
    void platformAdminFalseVanBi403() throws Exception {
        mvc.perform(get("/api/platform/tenants").with(jwt().jwt(j -> j.claim("platformAdmin", false))))
                .andExpect(status().isForbidden());
    }

    private void verifyNoUseCaseCalled() {
        verifyNoInteractions(create, update, status, search, getTenant, addAdmin, removeAdmin, attach, organizations);
    }

    @Test
    void platformAdminKhongCanTenantIdGoiDuocMoiEndpoint() throws Exception {
        TenantResponse response = new TenantResponse(TENANT, "lib-a", "Thư viện A", null, TenantStatus.ACTIVE, 1,
                List.of("LENDING"));
        when(create.execute(any(), eq(OPERATOR))).thenReturn(response);
        when(search.execute(any())).thenReturn(new PageResponse<>(List.of(response), 1, 0, 20));
        when(getTenant.execute(TENANT)).thenReturn(new TenantDetailResponse(TENANT, "lib-a", "Thư viện A", null,
                TenantStatus.ACTIVE, 1, List.of(), List.of("LENDING")));
        when(update.execute(eq(TENANT), any(), eq(OPERATOR))).thenReturn(response);
        when(status.suspend(TENANT, OPERATOR)).thenReturn(response);
        when(status.activate(TENANT, OPERATOR)).thenReturn(response);
        when(addAdmin.execute(eq(TENANT), any(), eq(OPERATOR))).thenReturn(UUID.randomUUID());
        when(organizations.create(any(), eq(OPERATOR)))
                .thenReturn(new OrganizationResponse(UUID.randomUUID(), "Trường A", null));
        when(organizations.list()).thenReturn(List.of());
        when(organizations.get(any())).thenReturn(new OrganizationResponse(UUID.randomUUID(), "Trường A", null));
        when(organizations.rename(any(), any(), eq(OPERATOR)))
                .thenReturn(new OrganizationResponse(UUID.randomUUID(), "Trường A", null));

        mvc.perform(json(post("/api/platform/tenants"), CREATE_BODY).with(platformAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("lib-a"))
                .andExpect(jsonPath("$.memberCount").value(1));
        mvc.perform(get("/api/platform/tenants").param("keyword", "lib").param("status", "ACTIVE")
                        .with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("lib-a"));
        mvc.perform(get("/api/platform/tenants/" + TENANT).with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(json(put("/api/platform/tenants/" + TENANT), UPDATE_BODY).with(platformAdmin()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/platform/tenants/" + TENANT + "/suspend").with(platformAdmin()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/platform/tenants/" + TENANT + "/activate").with(platformAdmin()))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/platform/tenants/" + TENANT + "/admins"), ADMIN_BODY).with(platformAdmin()))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/platform/tenants/" + TENANT + "/admins/" + UUID.randomUUID()).with(platformAdmin()))
                .andExpect(status().isNoContent());
        mvc.perform(json(post("/api/platform/tenants/" + TENANT + "/members"), MEMBER_BODY).with(platformAdmin()))
                .andExpect(status().isNoContent());
        mvc.perform(json(post("/api/platform/organizations"), ORG_BODY).with(platformAdmin()))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/platform/organizations").with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(get("/api/platform/organizations/" + UUID.randomUUID()).with(platformAdmin()))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/platform/organizations/" + UUID.randomUUID()), ORG_BODY).with(platformAdmin()))
                .andExpect(status().isOk());

        verify(search).execute(new TenantSearchRequest("lib", TenantStatus.ACTIVE, null, null, null));
        // khóa permission thường không được đăng ký cho endpoint nền tảng
        verifyNoInteractions(permissionKeyRegistrar);
    }

    @Test
    void codeTrungTraVe409() throws Exception {
        when(create.execute(any(CreateTenantRequest.class), eq(OPERATOR)))
                .thenThrow(new DuplicateTenantException("Mã tenant đã tồn tại: lib-a"));

        mvc.perform(json(post("/api/platform/tenants"), CREATE_BODY).with(platformAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TENANT"));
    }

    @Test
    void bodyThieuCodeBi400() throws Exception {
        mvc.perform(json(post("/api/platform/tenants"), "{\"name\":\"X\"}").with(platformAdmin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }
}
