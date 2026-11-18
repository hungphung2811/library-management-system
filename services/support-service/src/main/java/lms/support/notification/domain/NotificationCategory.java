package lms.support.notification.domain;

/** Nhóm thông báo để người dùng tắt/bật nhận. Nhóm bắt buộc (ACCOUNT, REFUND) không tắt được (ADR-0030 D7). */
public enum NotificationCategory {
    ORDER(false),
    INVOICE(false),
    LOAN_REMINDER(false),
    RESERVATION(false),
    FINE(false),
    REFUND(true),
    ACCOUNT(true);

    private final boolean mandatory;

    NotificationCategory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public boolean mandatory() {
        return mandatory;
    }
}
