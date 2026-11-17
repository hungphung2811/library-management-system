package lms.identity.user.application;

/**
 * Tự đăng ký bị từ chối ở tầng user (ngoại lệ tầng application để context khác dùng mà không đụng domain của user).
 * DUPLICATE: tên đăng nhập/email/id đã có ở bất kỳ tenant nào (thông điệp chung); INVALID: dữ liệu vi phạm quy tắc hồ sơ.
 */
public class SelfRegistrationRejectedException extends RuntimeException {

    public enum Reason { DUPLICATE, INVALID }

    private final Reason reason;

    public SelfRegistrationRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
