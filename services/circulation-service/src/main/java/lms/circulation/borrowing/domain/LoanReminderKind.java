package lms.circulation.borrowing.domain;

/** Loại nhắc hạn trả (ADR-0030 D11), lưu ở loan_reminders.kind. */
public enum LoanReminderKind {
    /** Sắp đến hạn trả. */
    DUE_SOON,
    /** Đã quá hạn trả. */
    OVERDUE
}
