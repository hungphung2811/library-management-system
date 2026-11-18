package lms.support.notification.domain;

public class PreferenceMandatoryException extends RuntimeException {

    public PreferenceMandatoryException(NotificationCategory category) {
        super("Nhóm thông báo " + category + " là bắt buộc, không tắt được");
    }
}
