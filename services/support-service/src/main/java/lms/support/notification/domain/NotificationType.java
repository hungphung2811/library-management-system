package lms.support.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Loại thông báo đợt đầu (ADR-0030 D10). Mỗi loại thuộc một nhóm và có danh sách tham số mẫu được phép dùng
 * (dạng đôi ngoặc nhọn quanh tên tham số): chỉ số chứng từ, ngày, số tiền, số lượng, tên; không có địa chỉ, điện thoại,
 * ghi chú hay lý do tự do (D6). {@code recipient_name} và {@code tenant_name} luôn dùng được, do dispatcher điền lúc gửi.
 */
public enum NotificationType {
    ORDER_PLACED(NotificationCategory.ORDER, "order_no", "grand_total"),
    ORDER_PAID(NotificationCategory.ORDER, "order_no", "grand_total"),
    ORDER_READY_FOR_PICKUP(NotificationCategory.ORDER, "order_no"),
    ORDER_SHIPPING(NotificationCategory.ORDER, "order_no"),
    ORDER_COMPLETED(NotificationCategory.ORDER, "order_no"),
    ORDER_CANCELLED(NotificationCategory.ORDER, "order_no", "cancel_reason"),
    REFUND_COMPLETED(NotificationCategory.REFUND, "order_no", "amount"),
    INVOICE_ISSUED(NotificationCategory.INVOICE, "invoice_no", "order_no", "grand_total", "issued_at"),
    LOAN_DUE_SOON(NotificationCategory.LOAN_REMINDER, "due_date", "days_before", "item_count"),
    LOAN_OVERDUE(NotificationCategory.LOAN_REMINDER, "due_date", "days_overdue", "reminder_no", "max_reminders",
            "item_count"),
    RESERVATION_READY(NotificationCategory.RESERVATION, "hold_until"),
    FINE_ISSUED(NotificationCategory.FINE, "amount", "reason"),
    CONTRACT_EXPIRING(NotificationCategory.ACCOUNT, "contract_name", "end_date", "days");

    /** Tham số do hệ thống điền lúc gửi, mọi loại đều được dùng. */
    public static final Set<String> COMMON_PARAMS = Set.of("recipient_name", "tenant_name");

    private final NotificationCategory category;
    private final Set<String> ownParams;

    NotificationType(NotificationCategory category, String... params) {
        this.category = category;
        this.ownParams = Set.of(params);
    }

    public NotificationCategory category() {
        return category;
    }

    /** Tham số lưu trong delivery (không gồm tham số chung). */
    public Set<String> storedParams() {
        return ownParams;
    }

    public boolean allows(String param) {
        return ownParams.contains(param) || COMMON_PARAMS.contains(param);
    }

    public List<String> allowedParams() {
        return java.util.stream.Stream.concat(ownParams.stream().sorted(), COMMON_PARAMS.stream().sorted()).toList();
    }

    public static Optional<NotificationType> parse(String code) {
        if (code == null) return Optional.empty();
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
