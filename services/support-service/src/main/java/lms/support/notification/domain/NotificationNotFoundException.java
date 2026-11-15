package lms.support.notification.domain;

/** Không có thông báo này của người dùng (không tồn tại, của người khác hoặc của tenant khác: không phân biệt). */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("Không tìm thấy thông báo");
    }
}
