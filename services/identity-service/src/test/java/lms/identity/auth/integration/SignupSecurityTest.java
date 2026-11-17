package lms.identity.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ChangePasswordUseCase;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.SignupUseCase;
import lms.identity.auth.application.SwitchTenantSessionUseCase;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.InvalidSignupException;
import lms.identity.auth.domain.SignupNotAvailableException;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.infrastructure.web.AuthController;
import lms.identity.config.SecurityConfiguration;
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

/**
 * POST /internal/auth/signup (ADR-0010 B2.1): công khai (không token, kiểm khóa bật), body allowlist (trường lạ bị bỏ
 * qua, không bao giờ tới use case), một mã chung SIGNUP_NOT_AVAILABLE, trùng/không hợp lệ có mã riêng. Cộng kênh token
 * ở login/refresh.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "lms.security.permission.require-tenant=true"})
class SignupSecurityTest {

    static final String PATH = "/internal/auth/signup";
    static final String BODY = "{\"tenantCode\":\"shop\",\"username\":\"bob\",\"password\":\"secret123\","
            + "\"fullName\":\"Bob\",\"email\":\"bob@example.com\"}";

    @Autowired MockMvc mvc;

    @MockitoBean SignupUseCase signup;
    @MockitoBean LoginUseCase login;
    @MockitoBean RefreshTokenUseCase refresh;
    @MockitoBean LogoutUseCase logout;
    @MockitoBean RegisterCredentialUseCase register;
    @MockitoBean ChangePasswordUseCase changePassword;
    @MockitoBean lms.identity.auth.application.RequestPasswordResetUseCase requestPasswordReset;
    @MockitoBean lms.identity.auth.application.ConfirmPasswordResetUseCase confirmPasswordReset;
    @MockitoBean SwitchTenantSessionUseCase switchTenant;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void congKhaiKhongTokenTra201KhongThan() throws Exception {
        when(signup.execute(any())).thenReturn(UUID.randomUUID());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));

        ArgumentCaptor<SignupUseCase.Command> c = ArgumentCaptor.forClass(SignupUseCase.Command.class);
        verify(signup).execute(c.capture());
        assertThat(c.getValue().tenantCode()).isEqualTo("shop");
        assertThat(c.getValue().username()).isEqualTo("bob");
        assertThat(c.getValue().fullName()).isEqualTo("Bob");
        assertThat(c.getValue().email()).isEqualTo("bob@example.com");
        // toString không lộ mật khẩu/họ tên/email
        assertThat(c.getValue().toString()).doesNotContain("secret123").doesNotContain("bob@example.com");
    }

    @Test
    void truongLaBiBoQuaKhongCoDuongNaoTuCapRoleHayTenant() throws Exception {
        when(signup.execute(any())).thenReturn(UUID.randomUUID());
        String body = "{\"tenantCode\":\"shop\",\"username\":\"bob\",\"password\":\"secret123\",\"fullName\":\"Bob\","
                + "\"email\":\"bob@example.com\",\"roles\":[\"OWNER\"],\"roleId\":\"" + UUID.randomUUID() + "\","
                + "\"admin\":true,\"isAdmin\":true,\"platformAdmin\":true,\"tenantId\":\"" + UUID.randomUUID() + "\","
                + "\"signupDefault\":true,\"mustChangePassword\":false,\"departmentId\":\"" + UUID.randomUUID() + "\","
                + "\"status\":\"ACTIVE\",\"permissions\":[\"AccessControlController/assignRole\"]}";

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());

        ArgumentCaptor<SignupUseCase.Command> c = ArgumentCaptor.forClass(SignupUseCase.Command.class);
        verify(signup).execute(c.capture());
        // Command chỉ có 5 trường allowlist: không có chỗ nào để mang role/tenantId/flag tới use case
        assertThat(SignupUseCase.Command.class.getRecordComponents()).extracting(rc -> rc.getName())
                .containsExactly("tenantCode", "username", "password", "fullName", "email");
        assertThat(c.getValue().tenantCode()).isEqualTo("shop");
    }

    @Test
    void thieuHoacSaiTruongTra400KhongGoiUseCase() throws Exception {
        String[] bad = {
                "{}",
                "{\"tenantCode\":\"shop\",\"username\":\"bob\",\"password\":\"short\",\"fullName\":\"Bob\",\"email\":\"b@e.com\"}",
                "{\"tenantCode\":\"shop\",\"username\":\"bob\",\"password\":\"secret123\",\"fullName\":\"Bob\",\"email\":\"khong-phai-email\"}",
                "{\"tenantCode\":\"shop\",\"username\":\"bob\",\"password\":\"secret123\",\"fullName\":\" \",\"email\":\"b@e.com\"}",
                "{\"username\":\"bob\",\"password\":\"secret123\",\"fullName\":\"Bob\",\"email\":\"b@e.com\"}",
                "{\"tenantCode\":\"shop\",\"username\":\"bob\",\"fullName\":\"Bob\",\"email\":\"b@e.com\"}"};
        for (String body : bad) {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(signup);
    }

    @Test
    void khongDangKyDuocLaMotMaChung403() throws Exception {
        when(signup.execute(any())).thenThrow(new SignupNotAvailableException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIGNUP_NOT_AVAILABLE"))
                .andExpect(jsonPath("$.message").value(SignupNotAvailableException.MESSAGE))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("shop"))));
    }

    @Test
    void trungTaiKhoanLaMaChungKhongNeuTenantNaoGiuTen() throws Exception {
        when(signup.execute(any())).thenThrow(DuplicateUsernameException.generic());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bob"))));
    }

    @Test
    void hoSoKhongHopLeTra400() throws Exception {
        when(signup.execute(any())).thenThrow(new InvalidSignupException("Tên đăng nhập gồm chữ thường"));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void chiMoDungMotDuongDanCongKhaiMoi() throws Exception {
        // đường dẫn lân cận không phải công khai: không token -> 401 (không bị permitAll nhầm)
        mvc.perform(post("/internal/auth/signup/extra").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/signups").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(signup);
    }

    // ---- kênh token ở đăng nhập / làm mới ----

    @Test
    void dangNhapTruyenKenhStorefrontVaMacDinhLaStaff() throws Exception {
        when(login.execute(any(), any(), any(), any(), any())).thenReturn(TokenResponse.bearer("a", "r", 900));

        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret123\",\"tenantCode\":\"shop\",\"channel\":\"STOREFRONT\"}"))
                .andExpect(status().isOk());
        verify(login).execute(eq("alice"), eq("secret123"), eq("shop"), eq(TokenChannel.STOREFRONT), any());

        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret123\"}")).andExpect(status().isOk());
        verify(login).execute(eq("alice"), eq("secret123"), org.mockito.ArgumentMatchers.isNull(), eq(TokenChannel.STAFF), any());
    }

    @Test
    void kenhLaTra400() throws Exception {
        mvc.perform(post("/internal/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"secret123\",\"channel\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"r\",\"channel\":\"ADMIN\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(login);
        verify(refresh, never()).execute(any(), any(), any(), any());
    }

    @Test
    void lamMoiTruyenKenh() throws Exception {
        when(refresh.execute(any(), any(), any(), any())).thenReturn(TokenResponse.bearer("a", "r2", 900));

        mvc.perform(post("/internal/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"r\",\"channel\":\"STOREFRONT\"}")).andExpect(status().isOk());
        verify(refresh).execute(eq("r"), eq(TokenChannel.STOREFRONT), org.mockito.ArgumentMatchers.isNull(), any());

        mvc.perform(post("/internal/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"r\"}")).andExpect(status().isOk());
        verify(refresh).execute(eq("r"), eq(TokenChannel.STAFF), org.mockito.ArgumentMatchers.isNull(), any());

        // tenantId (ADR-0013 2.4) được chuyển xuống use case khi có
        java.util.UUID tenant = java.util.UUID.randomUUID();
        mvc.perform(post("/internal/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"r\",\"channel\":\"STOREFRONT\",\"tenantId\":\"" + tenant + "\"}"))
                .andExpect(status().isOk());
        verify(refresh).execute(eq("r"), eq(TokenChannel.STOREFRONT), eq(tenant), any());
    }
}
