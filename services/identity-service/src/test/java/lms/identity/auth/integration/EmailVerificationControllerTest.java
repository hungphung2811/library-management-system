package lms.identity.auth.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.identity.auth.application.EmailVerificationUseCase;
import lms.identity.auth.domain.InvalidEmailVerificationException;
import lms.identity.auth.infrastructure.web.EmailVerificationController;
import lms.identity.config.SecurityConfiguration;
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

/**
 * POST /internal/auth/email-verifications/{confirm,resend}: công khai (không token, kiểm khóa bật), confirm 204 hoặc một
 * 400 EMAIL_VERIFICATION_FAILED duy nhất, resend luôn 202.
 */
@WebMvcTest(EmailVerificationController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "lms.security.permission.require-tenant=true"})
class EmailVerificationControllerTest {

    static final String CONFIRM = "/internal/auth/email-verifications/confirm";
    static final String RESEND = "/internal/auth/email-verifications/resend";

    @Autowired MockMvc mvc;

    @MockitoBean EmailVerificationUseCase verification;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @Test
    void confirmCongKhaiKhongTokenTra204() throws Exception {
        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"otp\":\"123456\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(verification).verify("bob", "123456");
    }

    @Test
    void confirmBangEmail() throws Exception {
        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\",\"otp\":\"123456\"}"))
                .andExpect(status().isNoContent());

        verify(verification).verify("bob@example.com", "123456");
    }

    @Test
    void confirmSaiLaMotMaMotThongDiepChung() throws Exception {
        doThrow(new InvalidEmailVerificationException()).when(verification).verify(any(), any());

        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"otp\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.message").value(InvalidEmailVerificationException.MESSAGE));
    }

    @Test
    void confirmThieuOtpTra400KhongGoiUseCase() throws Exception {
        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(verification);
    }

    @Test
    void resendLuonTra202KhongThan() throws Exception {
        mvc.perform(post(RESEND).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ghost\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(verification).resend("ghost");
    }

    @Test
    void resendBangEmailLuon202() throws Exception {
        mvc.perform(post(RESEND).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ghost@example.com\"}"))
                .andExpect(status().isAccepted());

        verify(verification).resend("ghost@example.com");
    }
}
