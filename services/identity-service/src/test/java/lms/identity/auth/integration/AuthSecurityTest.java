package lms.identity.auth.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.infrastructure.web.AuthController;
import lms.identity.config.SecurityConfiguration;
import java.time.Instant;
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

@WebMvcTest(AuthController.class)
@Import(SecurityConfiguration.class)
class AuthSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean LoginUseCase login;
    @MockitoBean RefreshTokenUseCase refresh;
    @MockitoBean LogoutUseCase logout;
    @MockitoBean RegisterCredentialUseCase register;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String LOGIN_BODY = "{\"username\":\"alice\",\"password\":\"secret123\"}";
    private final String registerBody = "{\"userId\":\"" + UUID.randomUUID()
            + "\",\"username\":\"bob\",\"password\":\"secret123\"}";

    private RequestPostProcessor with(String... permissions) {
        return jwt().jwt(j -> j.claim("permissions", List.of(permissions)))
                .authorities(Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    @Test
    void dangNhapLaCongKhaiVaTraToken() throws Exception {
        when(login.execute("alice", "secret123")).thenReturn(TokenResponse.bearer("a", "r", 900));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void saiThongTinTra401KhongLoChiTiet() throws Exception {
        when(login.execute(any(), any())).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void taiKhoanBiKhoaTra423() throws Exception {
        when(login.execute(any(), any())).thenThrow(new AccountLockedException(Instant.now()));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isLocked());
    }

    @Test
    void taoCredentialCanDangNhapVaPermissionUserCreate() throws Exception {
        when(register.execute(any(), any(), any(), any())).thenReturn(UUID.randomUUID());

        mvc.perform(post("/api/auth/credentials").contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/credentials").with(with("USER_VIEW"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/credentials").with(with("USER_CREATE"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated());
    }

    @Test
    void duongDanKhacBiTuChoi() throws Exception {
        mvc.perform(get("/api/anything").with(with("USER_CREATE"))).andExpect(status().isForbidden());
    }
}
