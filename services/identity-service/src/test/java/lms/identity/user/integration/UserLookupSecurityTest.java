package lms.identity.user.integration;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import lms.identity.config.SecurityConfiguration;
import lms.identity.user.application.LookupUsersUseCase;
import lms.identity.user.application.UserNameView;
import lms.identity.user.infrastructure.web.UserController;
import lms.identity.user.infrastructure.web.UserExceptionHandler;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** POST /internal/users/lookup (khóa UserController/lookup, ADR-0018). Bật lại kiểm khóa; không cần Docker. */
@WebMvcTest(UserController.class)
@Import({SecurityConfiguration.class, UserExceptionHandler.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class UserLookupSecurityTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Autowired MockMvc mvc;

    @MockitoBean LookupUsersUseCase lookup;
    @MockitoBean lms.identity.user.application.CreateUserUseCase create;
    @MockitoBean lms.identity.user.application.UpdateUserUseCase update;
    @MockitoBean lms.identity.user.application.ChangeUserStatusUseCase changeStatus;
    @MockitoBean lms.identity.user.application.GetUserUseCase get;
    @MockitoBean lms.identity.user.application.SearchUsersUseCase search;
    @MockitoBean lms.identity.user.application.ResetUserPasswordUseCase resetPassword;
    @MockitoBean lms.identity.user.application.UnlockUserUseCase unlock;
    @MockitoBean lms.identity.user.application.RevokeUserSessionsInTenantUseCase revokeSessions;
    @MockitoBean lms.identity.user.application.ImportUsersUseCase importUsers;
    @MockitoBean lms.identity.user.application.ExportUsersUseCase exportUsers;
    @MockitoBean lms.identity.user.application.ListUserSessionsUseCase listSessions;
    @MockitoBean lms.identity.user.application.RevokeUserSessionUseCase revokeSession;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private RequestPostProcessor withKey(List<String> permissions) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                .claim("permissions", permissions))
                .authorities(permissions.stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toArray(org.springframework.security.core.GrantedAuthority[]::new));
    }

    private static String body(UUID... ids) {
        StringBuilder sb = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.length; i++) sb.append(i > 0 ? "," : "").append('"').append(ids[i]).append('"');
        return sb.append("]}").toString();
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post("/internal/users/lookup").contentType(MediaType.APPLICATION_JSON).content(body(USER)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thieuKhoaBi403() throws Exception {
        mvc.perform(post("/internal/users/lookup").contentType(MediaType.APPLICATION_JSON).content(body(USER))
                .with(withKey(List.of("UserController/search")))).andExpect(status().isForbidden());
        verifyNoInteractions(lookup);
    }

    @Test
    void coKhoaTraIdVaHoTen() throws Exception {
        when(lookup.execute(List.of(USER))).thenReturn(List.of(new UserNameView(USER, "Nguyễn Văn An")));
        mvc.perform(post("/internal/users/lookup").contentType(MediaType.APPLICATION_JSON).content(body(USER))
                        .with(withKey(List.of("UserController/lookup"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(USER.toString()))
                .andExpect(jsonPath("$[0].fullName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void loQuaLonBi400() throws Exception {
        UUID[] ids = new UUID[LookupUsersUseCase.MAX_IDS + 1];
        for (int i = 0; i < ids.length; i++) ids[i] = UUID.randomUUID();
        mvc.perform(post("/internal/users/lookup").contentType(MediaType.APPLICATION_JSON).content(body(ids))
                        .with(withKey(List.of("UserController/lookup"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lookup);
    }
}
