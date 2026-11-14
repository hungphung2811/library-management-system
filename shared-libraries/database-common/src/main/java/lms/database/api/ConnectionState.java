package lms.database.api;

/** Trạng thái vòng đời của một connection đã đăng ký (khác với health lúc chạy). */
public enum ConnectionState {
    /** Đã mở thành công. */
    UP,
    /** Bị tắt bằng cấu hình, không được mở. */
    DISABLED,
    /** Mở thất bại (khi fail-on-startup-error=false). */
    FAILED
}
