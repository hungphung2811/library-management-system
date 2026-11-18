package lms.customer.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.web.AuthController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Xác minh email bằng OTP qua customer-api (audit A3): công khai (không token), tenant lấy từ đường dẫn, mọi lỗi sai
 * cùng mã/thân, resend luôn 202, mã lỗi mới được chuyển tiếp mà không lộ chi tiết.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class})
class EmailVerificationWebTest {

    private static final String VERIFY = "/public/t/shop-a/auth/verify-email";
    private static final String RESEND = "/public/t/shop-a/auth/resend-verification";

    @Autowired MockMvc mvc;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void shopA() {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(UUID.randomUUID(), "shop-a", "Hiệu sách A", "svc-token-a", 300));
    }

    private static HttpClientErrorException downstream(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void verifyCongKhaiKhongTokenTra204VaChuyenDungTruong() throws Exception {
        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"khach1\",\"otp\":\"123456\",\"tenantCode\":\"khac\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));

        verify(auth).verifyEmail("khach1", null, "123456");
    }

    @Test
    void verifyBangEmail() throws Exception {
        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"an@example.vn\",\"otp\":\"654321\"}"))
                .andExpect(status().isNoContent());

        verify(auth).verifyEmail(null, "an@example.vn", "654321");
    }

    @Test
    void verifySaiDinhDangOtpHoacThieuTra400KhongGoiIdentity() throws Exception {
        for (String body : new String[] {"{}", "{\"username\":\"a\"}", "{\"username\":\"a\",\"otp\":\"12345\"}",
                "{\"username\":\"a\",\"otp\":\"abcdef\"}", "{\"username\":\"a\",\"otp\":\"1234567\"}"}) {
            mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(auth);
    }

    @Test
    void verifySaiChuyenTiepMotMaKhongLoChiTiet() throws Exception {
        doThrow(downstream(HttpStatus.BAD_REQUEST,
                "{\"code\":\"EMAIL_VERIFICATION_FAILED\",\"message\":\"user 42 attempts=5 hash=abc\"}"))
                .when(auth).verifyEmail(any(), any(), any());

        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ghost\",\"otp\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Mã xác minh không đúng hoặc đã hết hạn"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("attempts"))));
    }

    @Test
    void resendLuon202KhongThan() throws Exception {
        mvc.perform(post(RESEND).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ghost\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
        mvc.perform(post(RESEND).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ghost@example.vn\"}"))
                .andExpect(status().isAccepted());

        verify(auth).resendVerification("ghost", null);
        verify(auth).resendVerification(null, "ghost@example.vn");
    }

    @Test
    void cuaHangKhongKhaDungThiKhongGoiIdentity() throws Exception {
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());

        mvc.perform(post("/public/t/khong-co/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\",\"otp\":\"123456\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        mvc.perform(post("/public/t/khong-co/auth/resend-verification").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));

        verifyNoInteractions(auth);
    }

    @Test
    void dangNhapChuaXacMinhChuyenTiepEmailNotVerifiedKhongLoThongDiepThoCuaIdentity() throws Exception {
        when(auth.login(any(), any(), any())).thenThrow(downstream(HttpStatus.FORBIDDEN,
                "{\"code\":\"EMAIL_NOT_VERIFIED\",\"message\":\"internal detail userId=7\"}"));

        mvc.perform(post("/public/t/shop-a/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"khach1\",\"password\":\"mat-khau-1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("userId"))));
    }

    @Test
    void duongDanLaVanBiDong() throws Exception {
        mvc.perform(get(VERIFY)).andExpect(status().is4xxClientError());
        mvc.perform(post("/public/t/shop-a/auth/verify-email/extra").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(auth);
    }
}
