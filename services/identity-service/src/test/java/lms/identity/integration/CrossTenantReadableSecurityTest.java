package lms.identity.integration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PermissionManagementUseCase;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.infrastructure.web.AccessControlController;
import lms.identity.accesscontrol.infrastructure.web.AccessControlExceptionHandler;
import lms.identity.config.SecurityConfiguration;
import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.DepartmentNode;
import lms.identity.department.application.DepartmentResponse;
import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.identity.department.domain.DepartmentStatus;
import lms.identity.department.infrastructure.web.DepartmentController;
import lms.identity.department.infrastructure.web.DepartmentExceptionHandler;
import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.GetUserUseCase;
import lms.identity.user.application.ResetUserPasswordUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.application.UserPageResponse;
import lms.identity.user.application.UserResponse;
import lms.identity.user.domain.UserStatus;
import lms.identity.user.infrastructure.web.UserController;
import lms.identity.user.infrastructure.web.UserExceptionHandler;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ADR-0008: 5 endpoint GET danh sách gắn @CrossTenantReadable. Platform admin không tenant đi qua (không cần khóa,
 * tenantId sai định dạng thì 400 INVALID_TENANT_ID); người khác giữ nguyên hành vi cũ (TENANT_REQUIRED/FORBIDDEN) và
 * bỏ qua tenantId. Endpoint ghi và GET theo id không được mở.
 */
@WebMvcTest({DepartmentController.class, UserController.class, AccessControlController.class})
@Import({SecurityConfiguration.class, DepartmentExceptionHandler.class, UserExceptionHandler.class,
        AccessControlExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class CrossTenantReadableSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean CreateDepartmentUseCase createDept;
    @MockitoBean UpdateDepartmentUseCase updateDept;
    @MockitoBean ChangeDepartmentStatusUseCase changeDeptStatus;
    @MockitoBean GetDepartmentUseCase getDept;
    @MockitoBean ListDepartmentsUseCase listDept;
    @MockitoBean CreateUserUseCase createUser;
    @MockitoBean UpdateUserUseCase updateUser;
    @MockitoBean ChangeUserStatusUseCase changeUserStatus;
    @MockitoBean GetUserUseCase getUser;
    @MockitoBean SearchUsersUseCase searchUsers;
    @MockitoBean ResetUserPasswordUseCase resetPassword;
    @MockitoBean lms.identity.user.application.UnlockUserUseCase unlockUser;
    @MockitoBean lms.identity.user.application.RevokeUserSessionsInTenantUseCase revokeUserSessions;
    @MockitoBean lms.identity.user.application.ListUserSessionsUseCase listUserSessions;
    @MockitoBean lms.identity.user.application.RevokeUserSessionUseCase revokeUserSession;
    @MockitoBean lms.identity.user.application.ImportUsersUseCase importUsers;
    @MockitoBean lms.identity.user.application.LookupUsersUseCase lookupUsers;
    @MockitoBean lms.identity.user.application.ExportUsersUseCase exportUsers;
    @MockitoBean PermissionManagementUseCase permissions;
    @MockitoBean RoleManagementUseCase roles;
    @MockitoBean UserRoleUseCase userRoles;
    @MockitoBean GetUserAccessUseCase userAccess;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private static RequestPostProcessor platformNoTenant() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    private static RequestPostProcessor platformWithTenant() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true)
                .claim("tenantId", TENANT.toString()));
    }

    /** Platform admin đã chọn tenant và có role quản trị của tenant đó (admin=true): coi như admin tenant. */
    private static RequestPostProcessor platformWithTenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true)
                .claim("tenantId", TENANT.toString()).claim("admin", true));
    }

    private static RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                .claim("admin", true));
    }

    private static RequestPostProcessor plainNoKey() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                .claim("permissions", List.of("OtherController/other")));
    }

    private static RequestPostProcessor admin(boolean platform) {
        return platform ? platformNoTenant() : tenantAdmin();
    }

    private List<String> readEndpoints() {
        return List.of("/internal/users", "/internal/departments", "/internal/departments/tree", "/internal/roles",
                "/internal/permissions");
    }

    private void stubs() {
        when(listDept.list(null)).thenReturn(List.of());
        when(listDept.tree(false)).thenReturn(List.of(new DepartmentNode(UUID.randomUUID(), "IT", "CNTT",
                DepartmentStatus.ACTIVE, TENANT, List.of())));
        when(roles.list()).thenReturn(List.of(new RoleResponse(UUID.randomUUID(), "R", "d", false, List.of(), TENANT)));
        when(permissions.list()).thenReturn(List.of());
        when(searchUsers.execute(null, null, null, 0, 20)).thenReturn(new UserPageResponse(
                List.of(new UserResponse(UUID.randomUUID(), "u", "U", "u@x.vn", null, UserStatus.ACTIVE, TENANT)), 1, 0, 20));
    }

    @Test
    void platformAdminKhongTenantGoiDuocCaNamEndpointKhongCanKhoa() throws Exception {
        stubs();
        for (String url : readEndpoints()) {
            mvc.perform(get(url).with(platformNoTenant())).andExpect(status().isOk());
            mvc.perform(get(url).param("tenantId", OTHER.toString()).with(platformNoTenant())).andExpect(status().isOk());
        }
        mvc.perform(get("/internal/departments/tree").with(platformNoTenant()))
                .andExpect(jsonPath("$[0].tenantId").value(TENANT.toString()));
        mvc.perform(get("/internal/roles").with(platformNoTenant()))
                .andExpect(jsonPath("$[0].tenantId").value(TENANT.toString()));
        mvc.perform(get("/internal/users").with(platformNoTenant()))
                .andExpect(jsonPath("$.items[0].tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void platformAdminKhongTenantTenantIdSaiDinhDangBi400() throws Exception {
        for (String url : readEndpoints()) {
            mvc.perform(get(url).param("tenantId", "khong-phai-uuid").with(platformNoTenant()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
        }
        verifyNoInteractions(listDept, roles, permissions, searchUsers);
    }

    @Test
    void nguoiKhacKhongCoKhoaVanBi403DuGuiTenantId() throws Exception {
        for (String url : readEndpoints()) {
            mvc.perform(get(url).param("tenantId", OTHER.toString()).with(plainNoKey()))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(listDept, roles, permissions, searchUsers);
    }

    @Test
    void nguoiKhacTenantIdSaiDinhDangBiBoQuaKhongPhai400() throws Exception {
        stubs();
        for (String url : readEndpoints()) {
            mvc.perform(get(url).param("tenantId", "khong-phai-uuid").with(tenantAdmin())).andExpect(status().isOk());
            mvc.perform(get(url).param("tenantId", "khong-phai-uuid").with(platformWithTenantAdmin()))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void platformAdminDaChonTenantKhongCoKhoaVaKhongAdminBi403NhuNguoiThuong() throws Exception {
        for (String url : readEndpoints()) {
            mvc.perform(get(url).with(platformWithTenant())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(listDept, roles, permissions, searchUsers);
    }

    @Test
    void tokenKhongTenantKhongPhaiPlatformAdminVan403TenantRequired() throws Exception {
        RequestPostProcessor adminNoTenant = jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true));
        for (String url : readEndpoints()) {
            mvc.perform(get(url).with(adminNoTenant)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    @Test
    void endpointGhiVaGetTheoIdVanTenantRequiredVoiPlatformAdminKhongTenant() throws Exception {
        UUID id = UUID.randomUUID();
        List<MockHttpServletRequestBuilder> blocked = List.of(
                get("/internal/departments/" + id), get("/internal/users/" + id), get("/internal/roles/" + id),
                post("/internal/departments").contentType("application/json").content("{\"code\":\"IT\",\"name\":\"X\"}"),
                post("/internal/roles").contentType("application/json")
                        .content("{\"name\":\"ROLE_X\",\"description\":\"d\"}"));
        for (MockHttpServletRequestBuilder r : blocked) {
            mvc.perform(r.with(platformNoTenant())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
        verifyNoInteractions(createDept, getDept, getUser, roles);
    }

    @Test
    void lopUseCaseNhanLoiGoiBinhThuongKhiPlatformAdmin() throws Exception {
        stubs();
        mvc.perform(get("/internal/users").param("keyword", "").with(admin(true))).andExpect(status().isOk());
        verify(searchUsers).execute("", null, null, 0, 20);
    }
}
