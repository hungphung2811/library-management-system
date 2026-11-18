package lms.customer.auth.client;

import java.util.UUID;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.dto.AuthDtos.SignupRequest;

/**
 * Cổng sang identity /internal/auth/**. Mọi lời gọi của khách đều đặt {@code channel = STOREFRONT} (kênh token tách khỏi
 * nhân viên, docs/api/token-audience.md): BFF tự đặt, KHÔNG nhận từ client.
 */
public interface AuthClient {

    /**
     * Đăng nhập vào đúng tenant {@code tenantCode}. Sai mật khẩu, NO_TENANT_ACCESS, khóa và vô hiệu đều ném
     * PublicApiException 401 LOGIN_FAILED (không dò được trạng thái tài khoản, ADR-0011 2.13 phương án A).
     */
    IdentityTokenView login(String tenantCode, String username, String password);

    /**
     * Đăng ký tài khoản khách của tenant {@code tenantCode} (identity gán role mặc định của cửa hàng). Thành công không
     * có dữ liệu; lỗi (SIGNUP_NOT_AVAILABLE, DUPLICATE_USERNAME, VALIDATION_FAILED) do PublicExceptionHandler chuyển tiếp.
     */
    void signup(String tenantCode, SignupRequest request);

    /**
     * Xác minh email bằng OTP (audit A3). Sai mọi lý do đều là 400 EMAIL_VERIFICATION_FAILED cùng thân (identity cũng
     * vậy); {@code username} hoặc {@code email} (một trong hai) định danh tài khoản.
     */
    void verifyEmail(String username, String email, String otp);

    /** Gửi lại OTP. Identity luôn 202 dù tài khoản có tồn tại hay không; BFF cũng luôn trả 202. */
    void resendVerification(String username, String email);

    /** Làm mới phiên, không kiểm tenant (đường dẫn cũ {@code /public/auth/refresh}). */
    IdentityTokenView refresh(String refreshToken);

    /**
     * Làm mới phiên và yêu cầu identity kiểm refresh token thuộc đúng tenant {@code tenantId} TRƯỚC khi xoay vòng
     * (ADR-0013 2.4). Sai tenant: identity trả 401, token không bị xoay.
     */
    IdentityTokenView refresh(String refreshToken, UUID tenantId);

    void logout(String refreshToken);
}
