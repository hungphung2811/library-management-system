package lms.identity.accesscontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.accesscontrol.application.AccessCommands.CreateRole;
import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PermissionManagementUseCase;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.infrastructure.web.AccessControlController;
import lms.identity.accesscontrol.infrastructure.web.AccessControlExceptionHandler;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Phân quyền theo endpoint của identity-service: khóa tự sinh Controller/method, đăng ký khóa, cờ admin.
 * Không cần Docker.
 */
@WebMvcTest(AccessControlController.class)
@Import({SecurityConfiguration.class, AccessControlExceptionHandler.class, PermissionAutoConfiguration.class})
class AccessControlSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean PermissionManagementUseCase permissions;
    @MockitoBean RoleManagementUseCase roles;
    @MockitoBean UserRoleUseCase userRoles;
    @MockitoBean GetUserAccessUseCase userAccess;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar registrar;

    private static final String ROLE_BODY = "{\"name\":\"AUDITOR\",\"description\":\"Kiểm toán\"}";

    /** Token có đúng các khóa quyền (Controller/method) được liệt kê. */
    private RequestPostProcessor with(String... keys) {
        return jwt().jwt(j -> j.claim("permissions", List.of(keys)))
                .authorities(Arrays.stream(keys).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("admin", true));
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/roles")).andExpect(status().isUnauthorized());
    }

    @Test
    void khoaListRolesXemDuocNhungKhongTaoDuocRole() throws Exception {
        when(roles.list()).thenReturn(List.of());

        mvc.perform(get("/api/roles").with(with("AccessControlController/listRoles"))).andExpect(status().isOk());
        mvc.perform(post("/api/roles").with(with("AccessControlController/listRoles"))
                        .contentType(MediaType.APPLICATION_JSON).content(ROLE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void khoaDuocDangKyVoiTenControllerSlashMethod() throws Exception {
        when(roles.list()).thenReturn(List.of());

        mvc.perform(get("/api/roles").with(with("AccessControlController/listRoles"))).andExpect(status().isOk());

        verify(registrar).ensureRegistered(argThat(d -> d.key().equals("AccessControlController/listRoles")
                && d.source().equals("AUTO") && d.httpMethod().equals("GET") && d.pathPattern().equals("/api/roles")));
    }

    @Test
    void thieuKhoaVanDuocDangKyDeNguoiQuanTriGanQuyen() throws Exception {
        mvc.perform(post("/api/roles").with(with()).contentType(MediaType.APPLICATION_JSON).content(ROLE_BODY))
                .andExpect(status().isForbidden());

        verify(registrar).ensureRegistered(argThat(d -> d.key().equals("AccessControlController/createRole")));
    }

    @Test
    void ganVaThuHoiRoleTheoTungKhoa() throws Exception {
        UUID user = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        String body = "{\"roleId\":\"" + role + "\"}";

        mvc.perform(post("/api/access/users/" + user + "/roles").with(with("AccessControlController/grantPermission"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/access/users/" + user + "/roles").with(with("AccessControlController/assignRole"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/access/users/" + user + "/roles/" + role).with(with("AccessControlController/revokeRole")))
                .andExpect(status().isNoContent());
    }

    @Test
    void duongDanNgoaiApiBiTuChoiConApiKhongCoHandlerLa404() throws Exception {
        mvc.perform(get("/khong-ton-tai").with(admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/khong-ton-tai").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void khongConApiNoiBoLayQuyenNguoiDung() throws Exception {
        mvc.perform(get("/internal/access/users/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    // ---- cờ isAdmin ----

    @Test
    void taoRoleQuanTriDoNguoiKhongPhaiAdminGoiThiUseCaseNhanDuCoCoBaoNguoiGoi() throws Exception {
        when(roles.create(any())).thenReturn(new RoleResponse(UUID.randomUUID(), "SUPERVISOR", "Quản trị", true, List.of()));
        String body = "{\"name\":\"SUPERVISOR\",\"description\":\"Quản trị\",\"isAdmin\":true}";

        mvc.perform(post("/api/roles").with(with("AccessControlController/createRole"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.isAdmin").value(true));

        ArgumentCaptor<CreateRole> command = ArgumentCaptor.forClass(CreateRole.class);
        verify(roles).create(command.capture());
        assertThat(command.getValue().admin()).isTrue();
        assertThat(command.getValue().requestedByAdmin()).isFalse();
    }

    @Test
    void adminTaoRoleQuanTriVaKhongCanKhoaQuyen() throws Exception {
        when(roles.create(any())).thenReturn(new RoleResponse(UUID.randomUUID(), "SUPERVISOR", "Quản trị", true, List.of()));
        String body = "{\"name\":\"SUPERVISOR\",\"description\":\"Quản trị\",\"isAdmin\":true}";

        mvc.perform(post("/api/roles").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateRole> command = ArgumentCaptor.forClass(CreateRole.class);
        verify(roles).create(command.capture());
        assertThat(command.getValue().requestedByAdmin()).isTrue();
    }

    @Test
    void khongGuiIsAdminThiMacDinhLaFalse() throws Exception {
        when(roles.create(any())).thenReturn(new RoleResponse(UUID.randomUUID(), "AUDITOR", "Kiểm toán", List.of()));

        mvc.perform(post("/api/roles").with(admin()).contentType(MediaType.APPLICATION_JSON).content(ROLE_BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.isAdmin").value(false));

        ArgumentCaptor<CreateRole> command = ArgumentCaptor.forClass(CreateRole.class);
        verify(roles).create(command.capture());
        assertThat(command.getValue().admin()).isFalse();
    }

    @Test
    void ganRoleTruyenCoBaoNguoiGoiLaAdminChoUseCase() throws Exception {
        UUID user = UUID.randomUUID();
        String body = "{\"roleId\":\"" + UUID.randomUUID() + "\"}";

        mvc.perform(post("/api/access/users/" + user + "/roles").with(with("AccessControlController/assignRole"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/access/users/" + user + "/roles").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        ArgumentCaptor<UserRole> command = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoles, org.mockito.Mockito.times(2)).assign(command.capture());
        assertThat(command.getAllValues()).extracting(UserRole::callerIsAdmin).containsExactly(false, true);
    }
}
