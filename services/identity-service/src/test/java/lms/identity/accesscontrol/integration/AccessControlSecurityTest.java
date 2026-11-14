package lms.identity.accesscontrol.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PermissionManagementUseCase;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.infrastructure.web.AccessControlController;
import lms.identity.config.SecurityConfiguration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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

/** Kiểm chứng RBAC của chính access-control-service. Không cần Docker. */
@WebMvcTest(AccessControlController.class)
@Import(SecurityConfiguration.class)
class AccessControlSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean PermissionManagementUseCase permissions;
    @MockitoBean RoleManagementUseCase roles;
    @MockitoBean UserRoleUseCase userRoles;
    @MockitoBean GetUserAccessUseCase userAccess;
    @MockitoBean JwtDecoder jwtDecoder;

    private RequestPostProcessor with(String... permissions) {
        return jwt().jwt(j -> j.claim("permissions", List.of(permissions)))
                .authorities(Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/roles")).andExpect(status().isUnauthorized());
    }

    @Test
    void roleViewXemDuocNhungKhongTaoDuocRole() throws Exception {
        when(roles.list()).thenReturn(List.of());

        mvc.perform(get("/api/roles").with(with("ROLE_VIEW"))).andExpect(status().isOk());
        mvc.perform(post("/api/roles").with(with("ROLE_VIEW")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AUDITOR\",\"description\":\"Kiểm toán\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleCreateTaoDuocRole() throws Exception {
        when(roles.create(any())).thenReturn(new RoleResponse(UUID.randomUUID(), "AUDITOR", "Kiểm toán", List.of()));

        mvc.perform(post("/api/roles").with(with("ROLE_CREATE")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"AUDITOR\",\"description\":\"Kiểm toán\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void ganVaThuHoiRoleCanRoleAssign() throws Exception {
        UUID user = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        String body = "{\"roleId\":\"" + role + "\"}";

        mvc.perform(post("/api/access/users/" + user + "/roles").with(with("ROLE_UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/access/users/" + user + "/roles").with(with("ROLE_ASSIGN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/access/users/" + user + "/roles/" + role).with(with("ROLE_ASSIGN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void duongDanKhongKhaiBaoBiTuChoi() throws Exception {
        mvc.perform(get("/api/unknown").with(with("ROLE_VIEW", "ROLE_CREATE", "ROLE_UPDATE", "ROLE_ASSIGN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void khongConMoApiNoiBoRaNgoai() throws Exception {
        UUID user = UUID.randomUUID();
        when(userAccess.execute(user)).thenReturn(new UserAccessResponse(user, List.of("MEMBER"), List.of("BOOK_VIEW")));

        mvc.perform(get("/internal/access/users/" + user).with(with("ROLE_VIEW", "ROLE_ASSIGN")))
                .andExpect(status().isForbidden());
    }
}
