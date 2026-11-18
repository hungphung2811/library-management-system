package lms.identity.auth.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lms.identity.auth.application.EmailVerificationUseCase;
import lms.security.permission.PublicEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Xác minh email của khách tự đăng ký bằng OTP (audit A3). Công khai; không lộ tài khoản có tồn tại hay không:
 * confirm trả 204 hoặc một 400 EMAIL_VERIFICATION_FAILED duy nhất cho mọi lý do sai, resend LUÔN 202.
 */
@RestController
@RequestMapping("/internal/auth/email-verifications")
public class EmailVerificationController {

    /** Định danh là username HOẶC email (một trong hai, thiếu cả hai thì coi như sai). */
    public record ConfirmRequest(@Size(max = 100) String username, @Size(max = 255) String email,
                                 @NotBlank @Size(max = 12) String otp) {
        @Override
        public String toString() {
            return "ConfirmRequest[***]";
        }
    }

    public record ResendRequest(@Size(max = 100) String username, @Size(max = 255) String email) {
        @Override
        public String toString() {
            return "ResendRequest[***]";
        }
    }

    private final EmailVerificationUseCase verification;

    public EmailVerificationController(EmailVerificationUseCase verification) {
        this.verification = verification;
    }

    @PublicEndpoint
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmRequest request) {
        verification.verify(identifier(request.username(), request.email()), request.otp());
        return ResponseEntity.noContent().build();
    }

    @PublicEndpoint
    @PostMapping("/resend")
    public ResponseEntity<Void> resend(@Valid @RequestBody ResendRequest request) {
        verification.resend(identifier(request.username(), request.email()));
        return ResponseEntity.accepted().build();
    }

    private static String identifier(String username, String email) {
        return username != null && !username.isBlank() ? username : email;
    }
}
