package lms.identity.auth.integration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.auth.application.ChangePasswordUseCase;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.domain.WrongCurrentPasswordException;
import lms.identity.auth.infrastructure.web.AuthController;
import lms.identity.auth.infrastructure.web.AuthExceptionHandler;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** POST /internal/auth/password chỉ cần đăng nhập (không cần permission), userId lấy từ token. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, AuthExceptionHandler.class, PermissionAutoConfiguration.class})
class ChangePasswordSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @MockitoBean LoginUseCase login;
    @MockitoBean RefreshTokenUseCase refresh;
    @MockitoBean LogoutUseCase logout;
    @MockitoBean RegisterCredentialUseCase register;
    @MockitoBean ChangePasswordUseCase changePassword;
    @MockitoBean lms.identity.auth.application.RequestPasswordResetUseCase requestPasswordReset;
    @MockitoBean lms.identity.auth.application.ConfirmPasswordResetUseCase confirmPasswordReset;
    @MockitoBean lms.identity.auth.application.SwitchTenantSessionUseCase switchTenant;
    @MockitoBean lms.identity.auth.application.SignupUseCase signup;
    @MockitoBean JwtDecoder jwtDecoder;

    static final UUID USER = UUID.randomUUID();
    static final String BODY = "{\"currentPassword\":\"OldPass123\",\"newPassword\":\"NewPass456\"}";

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(post("/internal/auth/password").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenKhongCoPermissionVanDoiDuocMatKhauVaDungUserIdTrongToken() throws Exception {
        mvc.perform(post("/internal/auth/password").with(jwt().jwt(j -> j.subject(USER.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());
        verify(changePassword).execute(eq(USER), eq("OldPass123"), eq("NewPass456"));
    }

    @Test
    void saiMatKhauHienTaiTra422VaMatKhauMoiNganBi400() throws Exception {
        doThrow(new WrongCurrentPasswordException()).when(changePassword).execute(eq(USER), eq("OldPass123"), eq("NewPass456"));
        var token = jwt().jwt(j -> j.subject(USER.toString()));
        mvc.perform(post("/internal/auth/password").with(token).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WRONG_CURRENT_PASSWORD"));
        mvc.perform(post("/internal/auth/password").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }
}
