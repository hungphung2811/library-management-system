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
import lms.identity.auth.application.SwitchTenantSessionUseCase;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.infrastructure.web.AuthController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
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
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
class AuthSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean LoginUseCase login;
    @MockitoBean RefreshTokenUseCase refresh;
    @MockitoBean LogoutUseCase logout;
    @MockitoBean RegisterCredentialUseCase register;
    @MockitoBean lms.identity.auth.application.ChangePasswordUseCase changePassword;
    @MockitoBean SwitchTenantSessionUseCase switchTenant;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static final String LOGIN_BODY = "{\"username\":\"alice\",\"password\":\"secret123\"}";
    private final String registerBody = "{\"userId\":\"" + UUID.randomUUID()
            + "\",\"username\":\"bob\",\"password\":\"secret123\"}";

    private RequestPostProcessor with(String... permissions) {
        return jwt().jwt(j -> j.claim("permissions", List.of(permissions)))
                .authorities(Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    @Test
    void dangNhapLaCongKhaiVaTraToken() throws Exception {
        when(login.execute("alice", "secret123", null)).thenReturn(TokenResponse.bearer("a", "r", 900));

        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void saiThongTinTra401KhongLoChiTiet() throws Exception {
        when(login.execute(any(), any(), any())).thenThrow(new InvalidCredentialsException());

        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void taiKhoanBiKhoaTra423() throws Exception {
        when(login.execute(any(), any(), any())).thenThrow(new AccountLockedException(Instant.now()));

        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isLocked());
    }

    @Test
    void taoCredentialCanDangNhapVaPermissionUserCreate() throws Exception {
        when(register.execute(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(UUID.randomUUID());

        mvc.perform(post("/internal/auth/credentials").contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/credentials").with(with("AuthController/changePasswordKhac"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/auth/credentials").with(with("AuthController/register"))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated());
    }

    @Test
    void dangNhapTheoMaTenantTraTenantIdVaDanhSachTenant() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(login.execute("alice", "secret123", "lib-a")).thenReturn(TokenResponse.bearer("a", "r", 900, false, tenant,
                List.of(new TenantSummary(tenant, "lib-a", "Thư viện A"))));

        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\",\"tenantCode\":\"lib-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"))
                .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
                .andExpect(jsonPath("$.availableTenants[0].code").value("lib-a"))
                .andExpect(jsonPath("$.availableTenants[0].name").value("Thư viện A"));
    }

    @Test
    void khongTruyCapDuocTenantTra403NoTenantAccess() throws Exception {
        when(login.execute(any(), any(), any())).thenThrow(new NoTenantAccessException());

        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_TENANT_ACCESS"))
                .andExpect(jsonPath("$.message").value(new NoTenantAccessException().getMessage()));
    }

    @Test
    void doiTenantChiCanDangNhapKeCaTokenKhongCoTenantId() throws Exception {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        String body = "{\"tenantId\":\"" + tenant + "\",\"refreshToken\":\"r1\"}";
        when(switchTenant.execute(user, tenant, "r1")).thenReturn(TokenResponse.bearer("a2", "r2", 900, false, tenant, List.of()));

        mvc.perform(post("/internal/auth/switch-tenant").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        // token không permission, không tenantId (ví dụ platform admin) vẫn đổi được
        mvc.perform(post("/internal/auth/switch-tenant").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a2"))
                .andExpect(jsonPath("$.tenantId").value(tenant.toString()));
    }

    @Test
    void doiSangTenantKhongCoQuyenTra403VaThieuTenantIdTra400() throws Exception {
        UUID user = UUID.randomUUID();
        when(switchTenant.execute(any(), any(), any())).thenThrow(new NoTenantAccessException());

        mvc.perform(post("/internal/auth/switch-tenant").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + UUID.randomUUID() + "\",\"refreshToken\":\"r1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_TENANT_ACCESS"));
        mvc.perform(post("/internal/auth/switch-tenant").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        // thiếu refreshToken cũng là 400
        mvc.perform(post("/internal/auth/switch-tenant").with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taoCredentialGanMembershipVaoTenantCuaNguoiTao() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(register.execute(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(UUID.randomUUID());

        mvc.perform(post("/internal/auth/credentials")
                        .with(jwt().jwt(j -> j.claim("permissions", List.of("AuthController/register"))
                                .claim("tenantId", tenant.toString()))
                                .authorities(new SimpleGrantedAuthority("AuthController/register")))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody))
                .andExpect(status().isCreated());

        org.mockito.Mockito.verify(register).execute(any(), org.mockito.ArgumentMatchers.eq("bob"), any(), any(),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(tenant));
    }

    @Test
    void duongDanNgoaiApiBiTuChoiConApiKhongCoHandlerLa404() throws Exception {
        mvc.perform(get("/anything").with(with("AuthController/register"))).andExpect(status().isForbidden());
        mvc.perform(get("/internal/anything").with(with("AuthController/register"))).andExpect(status().isNotFound());
    }
}
