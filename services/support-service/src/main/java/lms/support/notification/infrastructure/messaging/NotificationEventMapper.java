package lms.support.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lms.support.notification.application.NotificationTrigger;
import lms.support.notification.domain.NotificationType;

/**
 * Ánh xạ envelope sự kiện thành {@link NotificationTrigger} (ADR-0030 D10). Chỉ lấy id, mã chứng từ, ngày, số tiền, số
 * lượng: không có địa chỉ giao hàng, điện thoại, ghi chú, lý do tự do. Loại sự kiện chưa tồn tại hoặc thiếu trường
 * (ví dụ sales chưa làm giàu customerUserId) thì bỏ qua, không lỗi. Không log payload.
 */
final class NotificationEventMapper {

    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$");

    private NotificationEventMapper() {
    }

    // ---- sales.events ----

    static Optional<NotificationTrigger> fromSales(JsonNode root) {
        String eventType = root.path("eventType").asText("");
        Optional<NotificationType> type = switch (eventType) {
            case "OrderPlaced" -> Optional.of(NotificationType.ORDER_PLACED);
            case "OrderPaid" -> Optional.of(NotificationType.ORDER_PAID);
            case "OrderReadyForPickup" -> Optional.of(NotificationType.ORDER_READY_FOR_PICKUP);
            case "OrderShipping" -> Optional.of(NotificationType.ORDER_SHIPPING);
            case "OrderCompleted" -> Optional.of(NotificationType.ORDER_COMPLETED);
            case "OrderCancelled" -> Optional.of(NotificationType.ORDER_CANCELLED);
            case "RefundCompleted" -> Optional.of(NotificationType.REFUND_COMPLETED);
            case "InvoiceIssued" -> Optional.of(NotificationType.INVOICE_ISSUED);
            default -> Optional.empty();
        };
        UUID eventId = uuid(root.path("eventId"));
        JsonNode data = root.path("payload").path("data");
        UUID customer = uuid(data.path("customerUserId"));
        if (type.isEmpty() || eventId == null || customer == null) return Optional.empty();
        NotificationType t = type.get();
        // Đơn POS: khách đang đứng tại quầy, không gửi thông báo đơn (chỉ hóa đơn và hoàn tiền), ADR-0030 mục 7.4
        if (t.category() == lms.support.notification.domain.NotificationCategory.ORDER
                && "POS".equalsIgnoreCase(data.path("channel").asText(""))) {
            return Optional.empty();
        }
        Map<String, String> p = new HashMap<>();
        put(p, "order_no", text(data.path("orderNo")));
        put(p, "grand_total", money(data.path("grandTotal")));
        put(p, "amount", money(data.path("amount")));
        put(p, "invoice_no", text(data.path("invoiceNo")));
        put(p, "issued_at", date(data.path("issuedAt"), root.path("occurredAt")));
        put(p, "cancel_reason", cancelReason(data.path("cancelKind").asText("")));
        return Optional.of(NotificationTrigger.forUser(eventId, eventType, t, customer, onlyAllowed(t, p)));
    }

    // ---- borrowing.events (gói D) ----

    static Optional<NotificationTrigger> fromBorrowing(JsonNode root) {
        String eventType = root.path("eventType").asText("");
        NotificationType t = switch (eventType) {
            case "LoanDueSoon" -> NotificationType.LOAN_DUE_SOON;
            case "LoanOverdueReminder" -> NotificationType.LOAN_OVERDUE;
            default -> null;
        };
        UUID eventId = uuid(root.path("eventId"));
        JsonNode payload = root.path("payload");
        UUID reader = uuid(payload.path("readerId"));
        if (t == null || eventId == null || reader == null) return Optional.empty();
        Map<String, String> p = new HashMap<>();
        put(p, "due_date", date(payload.path("dueDate"), null));
        put(p, "days_before", number(payload.path("daysBefore")));
        put(p, "days_overdue", number(payload.path("daysOverdue")));
        put(p, "reminder_no", number(payload.path("reminderNo")));
        put(p, "max_reminders", number(payload.path("maxReminders")));
        put(p, "item_count", number(payload.path("itemCount")));
        return Optional.of(NotificationTrigger.forUser(eventId, eventType, t, reader, onlyAllowed(t, p)));
    }

    // ---- fine.events ----

    static Optional<NotificationTrigger> fromFine(JsonNode root) {
        if (!"FineIssuedEvent".equals(root.path("eventType").asText(""))) return Optional.empty();
        UUID eventId = uuid(root.path("eventId"));
        JsonNode payload = root.path("payload");
        UUID reader = uuid(payload.path("readerId"));
        if (eventId == null || reader == null) return Optional.empty();
        Map<String, String> p = new HashMap<>();
        put(p, "amount", money(payload.path("amount")));
        put(p, "reason", fineReason(payload.path("reason").asText("")));
        return Optional.of(NotificationTrigger.forUser(eventId, "FineIssuedEvent", NotificationType.FINE_ISSUED,
                reader, onlyAllowed(NotificationType.FINE_ISSUED, p)));
    }

    // ---- reservation.events ----

    static Optional<NotificationTrigger> fromReservation(JsonNode root) {
        if (!"ReservationReadyEvent".equals(root.path("eventType").asText(""))) return Optional.empty();
        UUID eventId = uuid(root.path("eventId"));
        JsonNode payload = root.path("payload");
        UUID reader = uuid(payload.path("readerId"));
        if (eventId == null || reader == null) return Optional.empty();
        String holdUntil = payload.path("holdUntil").asText("");
        Map<String, String> p = new HashMap<>();
        put(p, "hold_until", date(payload.path("holdUntil"), null));
        String inApp = holdUntil.isBlank() ? "Sách bạn đặt trước đã sẵn sàng để nhận."
                : "Sách bạn đặt trước đã sẵn sàng để nhận, được giữ đến " + holdUntil + ".";
        // eventId của thông báo trong ứng dụng giữ nguyên như trước (UNIQUE (tenant_id, event_id) đã có dữ liệu)
        return Optional.of(NotificationTrigger.forUser(eventId, "ReservationReadyEvent",
                NotificationType.RESERVATION_READY, reader, onlyAllowed(NotificationType.RESERVATION_READY, p))
                .withInApp(eventId, inApp));
    }

    // ---- identity-events: TenantContractExpiringSoon (mỗi quản trị viên một trigger) ----

    static List<NotificationTrigger> fromContractExpiring(JsonNode root) {
        if (!"TenantContractExpiringSoon".equals(root.path("eventType").asText(""))) return List.of();
        UUID eventId = uuid(root.path("eventId"));
        JsonNode payload = root.path("payload");
        if (eventId == null) return List.of();
        String name = payload.path("contractName").asText("");
        LocalDate end = LocalDate.parse(payload.path("endDate").asText());
        int days = payload.path("daysBefore").asInt();
        String inApp = "Hợp đồng thuê dịch vụ" + (name.isBlank() ? "" : " \"" + name + "\"") + " sẽ hết hạn vào "
                + VN_DATE.format(end) + " (còn khoảng " + days + " ngày). Vui lòng liên hệ để gia hạn.";
        Map<String, String> p = new HashMap<>();
        put(p, "contract_name", name.isBlank() ? "" : "\"" + name + "\"");
        put(p, "end_date", VN_DATE.format(end));
        put(p, "days", String.valueOf(days));
        List<NotificationTrigger> out = new ArrayList<>();
        for (JsonNode user : payload.path("recipientUserIds")) {
            UUID userId = uuid(user);
            if (userId == null) continue;
            // Một event mang danh sách người nhận: eventId thông báo trong ứng dụng dẫn xuất tất định như trước
            UUID perUser = UUID.nameUUIDFromBytes((eventId + ":" + userId).getBytes(StandardCharsets.UTF_8));
            out.add(NotificationTrigger.forUser(eventId, "TenantContractExpiringSoon",
                    NotificationType.CONTRACT_EXPIRING, userId, onlyAllowed(NotificationType.CONTRACT_EXPIRING, p))
                    .withInApp(perUser, inApp));
        }
        return out;
    }

    // ---- notification.direct: khách lẻ để lại email (không qua audit) ----

    static Optional<NotificationTrigger> fromDirect(JsonNode root) {
        JsonNode body = root.has("payload") && root.path("payload").isObject() ? root.path("payload") : root;
        UUID eventId = uuid(body.path("eventId"));
        if (eventId == null) eventId = uuid(root.path("eventId"));
        Optional<NotificationType> type = NotificationType.parse(body.path("notificationType").asText(null));
        String email = body.path("recipientEmail").asText("").trim();
        if (eventId == null || type.isEmpty() || email.length() > 255 || !EMAIL.matcher(email).matches()) {
            return Optional.empty();
        }
        Map<String, String> p = new HashMap<>();
        JsonNode params = body.path("params");
        params.fieldNames().forEachRemaining(k -> {
            JsonNode v = params.path(k);
            String key = snake(k);
            String value = v.isNumber() && (key.equals("grand_total") || key.equals("amount")) ? money(v)
                    : key.equals("issued_at") ? date(v, null) : text(v);
            put(p, key, value);
        });
        return Optional.of(NotificationTrigger.forAddress(eventId, "NotificationDirect", type.get(), email,
                onlyAllowed(type.get(), p)));
    }

    // ---- tiện ích ----

    /** Chỉ giữ tham số thuộc loại thông báo (chặn dữ liệu thừa lọt vào delivery) và cắt độ dài. */
    private static Map<String, String> onlyAllowed(NotificationType type, Map<String, String> params) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (type.storedParams().contains(e.getKey()) && e.getValue() != null) {
                String v = e.getValue();
                out.put(e.getKey(), v.length() > 200 ? v.substring(0, 200) : v);
            }
        }
        return out;
    }

    private static void put(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    static UUID uuid(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        try {
            return UUID.fromString(n.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String number(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() || !n.isNumber() ? null : String.valueOf(n.asLong());
    }

    /** Số tiền đồng: nhóm hàng nghìn bằng dấu chấm, ví dụ 1.250.000 đ. */
    static String money(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        try {
            BigDecimal v = n.isNumber() ? n.decimalValue() : new BigDecimal(n.asText());
            String plain = v.setScale(0, RoundingMode.HALF_UP).abs().toPlainString();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < plain.length(); i++) {
                if (i > 0 && (plain.length() - i) % 3 == 0) sb.append('.');
                sb.append(plain.charAt(i));
            }
            return (v.signum() < 0 ? "-" : "") + sb + " đ";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Ngày dd/MM/yyyy từ chuỗi ISO (ngày hoặc thời điểm); không có thì lấy từ dự phòng. */
    static String date(JsonNode n, JsonNode fallback) {
        String s = n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
        if ((s == null || s.isBlank()) && fallback != null && !fallback.isMissingNode() && !fallback.isNull()) {
            s = fallback.asText();
        }
        if (s == null || s.isBlank()) return null;
        try {
            return VN_DATE.format(LocalDate.parse(s));
        } catch (RuntimeException e) {
            try {
                return VN_DATE.format(Instant.parse(s).atZone(VN_ZONE));
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static String cancelReason(String kind) {
        return switch (kind) {
            case "CUSTOMER" -> "theo yêu cầu của bạn";
            case "STAFF" -> "do cửa hàng hủy";
            case "EXPIRED" -> "do hết thời hạn giữ hàng";
            case "PAYMENT_TIMEOUT" -> "do quá hạn thanh toán";
            default -> "";
        };
    }

    private static String fineReason(String reason) {
        return switch (reason) {
            case "LATE_RETURN" -> "trả sách trễ hạn";
            case "LOST" -> "làm mất sách";
            case "DAMAGED" -> "làm hư hỏng sách";
            default -> "";
        };
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
