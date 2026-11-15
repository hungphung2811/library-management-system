package lms.identity.tenant.application;

/** Lỗi về tài khoản trong thao tác nền tảng (tạo quản trị viên, gắn tài khoản có sẵn). */
public class PlatformAccountException extends RuntimeException {

    public enum Reason { DUPLICATE, NOT_FOUND, INVALID }

    private final Reason reason;

    public PlatformAccountException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
