package lms.identity.accesscontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lms.identity.accesscontrol.application.AccessCommands.DeleteRole;
import lms.identity.accesscontrol.application.AccessCommands.GrantPermission;
import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.application.AccessCommands.UpdateRole;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PermissionManagementUseCase;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.DuplicateAccessControlException;
import lms.identity.accesscontrol.domain.RoleConflictException;
import lms.identity.accesscontrol.infrastructure.web.AccessControlController;
import lms.identity.accesscontrol.infrastructure.web.AccessControlExceptionHandler;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
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

/** Khóa quyền và mã lỗi của sửa/xóa role (khóa AccessControlController/updateRole, deleteRole). Không cần Docker. */
@WebMvcTest(AccessControlController.class)
@Import({SecurityConfiguration.class, AccessControlExceptionHandler.class, PermissionAutoConfiguration.class})
@org.springframework.test.context.TestPropertySource(properties = "lms.security.permission.require-tenant=true")
class RoleUpdateDeleteSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean PermissionManagementUseCase permissions;
    @MockitoBean RoleManagementUseCase roles;
    @MockitoBean UserRoleUseCase userRoles;
    @MockitoBean GetUserAccessUseCase userAccess;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar registrar;

    private static final String TENANT = UUID.randomUUID().toString();
    private static final String BODY = "{\"name\":\"REVIEWER\",\"description\":\"Duyệt\"}";

    private RequestPostProcessor with(String... keys) {
        return jwt().jwt(j -> j.claim("permissions", List.of(keys)).claim("tenantId", TENANT))
                .authorities(Arrays.stream(keys).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", TENANT));
    }

    @Test
    void suaRoleCanDungKhoaUpdateRole() throws Exception {
        UUID id = UUID.randomUUID();
        when(roles.update(any())).thenReturn(new RoleResponse(id, "REVIEWER", "Duyệt", false, List.of()));

        mvc.perform(put("/internal/roles/" + id).with(with("AccessControlController/createRole"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(put("/internal/roles/" + id).with(with("AccessControlController/updateRole"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("REVIEWER"));

        ArgumentCaptor<UpdateRole> cmd = ArgumentCaptor.forClass(UpdateRole.class);
        verify(roles).update(cmd.capture());
        assertThat(cmd.getValue().requestedByAdmin()).isFalse();
        assertThat(cmd.getValue().roleId()).isEqualTo(id);
        verify(registrar, org.mockito.Mockito.atLeastOnce()).ensureRegistered(argThat(d -> d.key().equals("AccessControlController/updateRole")));
    }

    @Test
    void suaRoleKhongTenLa400VaAdminDuocBaoChoUseCase() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/internal/roles/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(roles.update(any())).thenReturn(new RoleResponse(id, "REVIEWER", "Duyệt", true, List.of()));
        mvc.perform(put("/internal/roles/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        ArgumentCaptor<UpdateRole> cmd = ArgumentCaptor.forClass(UpdateRole.class);
        verify(roles).update(cmd.capture());
        assertThat(cmd.getValue().requestedByAdmin()).isTrue();
    }

    @Test
    void suaTrungTenLa409Duplicate() throws Exception {
        when(roles.update(any())).thenThrow(new DuplicateAccessControlException("Role đã tồn tại: REVIEWER"));
        mvc.perform(put("/internal/roles/" + UUID.randomUUID()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE"));
    }

    @Test
    void xoaRoleCanDungKhoaDeleteRole() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(delete("/internal/roles/" + id).with(with("AccessControlController/updateRole")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/internal/roles/" + id).with(with("AccessControlController/deleteRole")))
                .andExpect(status().isNoContent());
        ArgumentCaptor<DeleteRole> cmd = ArgumentCaptor.forClass(DeleteRole.class);
        verify(roles).delete(cmd.capture());
        assertThat(cmd.getValue().roleId()).isEqualTo(id);
        assertThat(cmd.getValue().requestedByAdmin()).isFalse();
        verify(registrar, org.mockito.Mockito.atLeastOnce()).ensureRegistered(argThat(d -> d.key().equals("AccessControlController/deleteRole")));
    }

    @Test
    void xoaRoleDangDungLa409RoleInUseVaKhongThayLa404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new RoleConflictException("ROLE_IN_USE", "Role đang được gán")).when(roles).delete(any());
        mvc.perform(delete("/internal/roles/" + id).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_IN_USE"));
        doThrow(new AccessControlNotFoundException("Không tìm thấy role")).when(roles).delete(any());
        mvc.perform(delete("/internal/roles/" + id).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void themKhoaChuyenCoQuanTriChoUseCaseVaRoleDangKyBiTuChoiThanh403() throws Exception {
        UUID id = UUID.randomUUID();
        String body = "{\"permissionCode\":\"ShopController/placeOrder\"}";
        when(roles.grantPermission(any())).thenReturn(new RoleResponse(id, "CUSTOMER", "Khách", false, List.of()));

        mvc.perform(post("/internal/roles/" + id + "/permissions").with(with("AccessControlController/grantPermission"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/roles/" + id + "/permissions").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        ArgumentCaptor<GrantPermission> cmd = ArgumentCaptor.forClass(GrantPermission.class);
        verify(roles, org.mockito.Mockito.times(2)).grantPermission(cmd.capture());
        assertThat(cmd.getAllValues().get(0).requestedByAdmin()).isFalse();
        assertThat(cmd.getAllValues().get(1).requestedByAdmin()).isTrue();

        // use case từ chối role signup_default với người không admin: 403 FORBIDDEN theo khuôn exception hiện có
        when(roles.grantPermission(any())).thenThrow(new AccessControlForbiddenException("role đăng ký mặc định"));
        mvc.perform(post("/internal/roles/" + id + "/permissions").with(with("AccessControlController/grantPermission"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
