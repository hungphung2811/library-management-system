package lms.customer.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lms.customer.web.NoControlChars;

/** DTO đăng nhập/đăng ký khách. Body KHÔNG có tenantCode: tenant lấy từ đường dẫn (ADR-0011 2.2). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 72) String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=***]";
        }
    }

    /**
     * Body đăng ký tài khoản khách (allowlist): chỉ 4 trường này. Trường lạ trong JSON (tenantCode, role, tenantId, flag
     * ...) bị bỏ qua và không bao giờ tới identity; tenant lấy từ đường dẫn {@code /public/t/{code}}.
     */
    public record SignupRequest(
            @NotBlank @Size(min = 3, max = 100) @NoControlChars String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 255) @NoControlChars String fullName,
            @NotBlank @Email @Size(max = 255) @NoControlChars String email) {

        @Override
        public String toString() {
            return "SignupRequest[***]";
        }
    }

    /**
     * Xác minh email: một trong {@code username}/{@code email} cùng OTP 6 chữ số. Không có trường tenant (lấy từ đường
     * dẫn). Thiếu cả hai định danh thì identity trả cùng lỗi như OTP sai.
     */
    public record VerifyEmailRequest(
            @Size(max = 100) @NoControlChars String username,
            @Email @Size(max = 255) @NoControlChars String email,
            @NotBlank @Pattern(regexp = "\\d{6}") String otp) {

        @Override
        public String toString() {
            return "VerifyEmailRequest[***]";
        }
    }

    public record ResendVerificationRequest(
            @Size(max = 100) @NoControlChars String username,
            @Email @Size(max = 255) @NoControlChars String email) {

        @Override
        public String toString() {
            return "ResendVerificationRequest[***]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 2000) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[***]";
        }
    }

    /** Phản hồi của identity (nội bộ). Chỉ lấy các trường cần; bỏ availableTenants, mustChangePassword... */
    public record IdentityTokenView(String accessToken, String refreshToken, long expiresIn) {

        /** Đủ để trả cho khách: cả hai token không rỗng và expiresIn dương (thiếu trường thì Jackson cho 0). */
        public boolean isUsable() {
            return accessToken != null && !accessToken.isBlank() && refreshToken != null && !refreshToken.isBlank()
                    && expiresIn > 0;
        }

        @Override
        public String toString() {
            return "IdentityTokenView[***]";
        }
    }

    /** Phản hồi công khai: chỉ {accessToken, refreshToken, expiresIn}. */
    public record SessionView(String accessToken, String refreshToken, long expiresIn) {

        @Override
        public String toString() {
            return "SessionView[***]";
        }
    }
}
