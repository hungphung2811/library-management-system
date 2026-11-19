package lms.support.notification.infrastructure.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.ReaderPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.support.notification.application.DeliveryPort;
import lms.support.notification.application.NotificationTemplateUseCases;
import lms.support.notification.application.NotificationTransactions;
import lms.support.notification.application.SaveNotificationPort;
import lms.support.notification.domain.AddressMasker;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.Notification;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.SkipReason;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Seed thông báo của tenant demo (ADR-0018, ADR-0030 gói D): thông báo trong ứng dụng (đã đọc và chưa đọc), lịch sử
 * gửi email đủ trạng thái kết thúc SENT, DEAD, SKIPPED (đủ lý do bỏ qua) cho nhiều loại thông báo, và một mẫu thông
 * báo riêng của tenant. Không tạo dòng PENDING/RETRY/SENDING: dispatcher của support-service sẽ gửi email thật khi
 * chạy, nên dữ liệu mẫu chỉ ở trạng thái đã kết thúc. Người nhận là độc giả demo (địa chỉ chỉ lưu dạng che).
 * <p>
 * Idempotent theo khóa tất định: thông báo theo {@code eventId} {@code DemoIds.of("notification-event", n)}, delivery
 * theo (tenant, eventId, kênh, người nhận) của {@code DemoIds.of("delivery-event", n)}, mẫu theo nguồn TENANT.
 */
@Component
@Profile("demo-seed")
public class NotificationDemoSeedStep implements DemoSeedStep {

    private static final int RECIPIENTS = 8;

    /** Loại thông báo email, tham số lưu kèm và trạng thái kết thúc. */
    private record Spec(NotificationType type, Map<String, String> params, DeliveryStatus status, SkipReason skip) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec(NotificationType.ORDER_PLACED, Map.of("order_no", "DEMO-0001", "grand_total", "250.000"),
                    DeliveryStatus.SENT, null),
            new Spec(NotificationType.ORDER_PAID, Map.of("order_no", "DEMO-0002", "grand_total", "180.000"),
                    DeliveryStatus.SENT, null),
            new Spec(NotificationType.ORDER_READY_FOR_PICKUP, Map.of("order_no", "DEMO-0003"), DeliveryStatus.SENT, null),
            new Spec(NotificationType.ORDER_COMPLETED, Map.of("order_no", "DEMO-0004"), DeliveryStatus.SENT, null),
            new Spec(NotificationType.ORDER_CANCELLED, Map.of("order_no", "DEMO-0005", "cancel_reason", "Hết hàng"),
                    DeliveryStatus.SENT, null),
            new Spec(NotificationType.INVOICE_ISSUED, Map.of("invoice_no", "C26TDM-0000001", "order_no", "DEMO-0006",
                    "grand_total", "330.000", "issued_at", "2026-11-10"), DeliveryStatus.SENT, null),
            new Spec(NotificationType.REFUND_COMPLETED, Map.of("order_no", "DEMO-0007", "amount", "90.000"),
                    DeliveryStatus.SENT, null),
            new Spec(NotificationType.LOAN_DUE_SOON, Map.of("due_date", "2026-11-20", "days_before", "2",
                    "item_count", "2"), DeliveryStatus.SENT, null),
            new Spec(NotificationType.LOAN_OVERDUE, Map.of("due_date", "2026-11-05", "days_overdue", "12",
                    "reminder_no", "1", "max_reminders", "3", "item_count", "1"), DeliveryStatus.SENT, null),
            new Spec(NotificationType.RESERVATION_READY, Map.of("hold_until", "2026-11-22"), DeliveryStatus.SENT, null),
            new Spec(NotificationType.FINE_ISSUED, Map.of("amount", "20.000", "reason", "LATE_RETURN"),
                    DeliveryStatus.DEAD, null),
            new Spec(NotificationType.LOAN_OVERDUE, Map.of("due_date", "2026-11-01", "days_overdue", "16",
                    "reminder_no", "2", "max_reminders", "3", "item_count", "3"), DeliveryStatus.DEAD, null),
            new Spec(NotificationType.ORDER_SHIPPING, Map.of("order_no", "DEMO-0008"), DeliveryStatus.SKIPPED,
                    SkipReason.OPTED_OUT),
            new Spec(NotificationType.ORDER_PLACED, Map.of("order_no", "DEMO-0009", "grand_total", "75.000"),
                    DeliveryStatus.SKIPPED, SkipReason.NO_ADDRESS),
            new Spec(NotificationType.LOAN_DUE_SOON, Map.of("due_date", "2026-11-25", "days_before", "3",
                    "item_count", "1"), DeliveryStatus.SKIPPED, SkipReason.CHANNEL_DISABLED),
            new Spec(NotificationType.CONTRACT_EXPIRING, Map.of("contract_name", "Hợp đồng thuê dịch vụ Demo",
                    "end_date", "2026-12-09", "days", "20"), DeliveryStatus.SENT, null));

    /** Thông báo trong ứng dụng: loại, nội dung, đã đọc hay chưa. */
    private record InApp(String type, String message, boolean read) {
    }

    private static final List<InApp> IN_APP = List.of(
            new InApp("LOAN_DUE_SOON", "Phiếu mượn của bạn sắp đến hạn trả trong 2 ngày.", false),
            new InApp("LOAN_OVERDUE", "Phiếu mượn của bạn đã quá hạn, vui lòng trả sách sớm.", false),
            new InApp("RESERVATION_READY", "Sách bạn đặt trước đã sẵn sàng, giữ đến hết 3 ngày.", false),
            new InApp("FINE_ISSUED", "Bạn có một khoản phạt trả trễ chưa thanh toán.", true),
            new InApp("ORDER_COMPLETED", "Đơn hàng của bạn đã hoàn tất, cảm ơn bạn đã mua sách.", true),
            new InApp("ORDER_READY_FOR_PICKUP", "Đơn hàng của bạn đã sẵn sàng để nhận tại cửa hàng.", false));

    private final DeliveryPort deliveries;
    private final SaveNotificationPort notifications;
    private final NotificationTemplateUseCases templates;
    private final TransactionTemplate tx;

    public NotificationDemoSeedStep(DeliveryPort deliveries, SaveNotificationPort notifications,
                                    NotificationTemplateUseCases templates,
                                    @Qualifier(NotificationTransactions.MANAGER) PlatformTransactionManager manager) {
        this.deliveries = deliveries;
        this.notifications = notifications;
        this.templates = templates;
        this.tx = new TransactionTemplate(manager);
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String name() {
        return "notification.demo";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed thông báo cần lms.demo-seed.tenant-id");
        }
        DemoPlan plan = context.plan();
        Clock clock = context.clock();
        Instant now = clock.instant();
        int created = 0;
        int skipped = 0;

        for (int i = 0; i < IN_APP.size(); i++) {
            InApp n = IN_APP.get(i);
            ReaderPlan reader = plan.readers().get(i % RECIPIENTS);
            Instant createdAt = now.minus(Duration.ofHours(6L + 20L * i));
            Notification notification = new Notification(UUID.randomUUID(), DemoIds.of("notification-event", i + 1),
                    reader.id(), n.type(), n.message(), createdAt, n.read() ? createdAt.plus(Duration.ofHours(1)) : null);
            if (Boolean.TRUE.equals(tx.execute(s -> notifications.saveIfAbsent(notification)))) {
                created++;
            } else {
                skipped++;
            }
        }

        for (int i = 0; i < SPECS.size(); i++) {
            Spec spec = SPECS.get(i);
            ReaderPlan reader = plan.readers().get(i % RECIPIENTS);
            Instant createdAt = now.minus(Duration.ofHours(5L + 40L * i));
            Delivery delivery = build(DemoIds.of("delivery-event", i + 1), spec, reader, createdAt);
            if (Boolean.TRUE.equals(tx.execute(s -> deliveries.insertIfAbsent(delivery)))) {
                created++;
            } else {
                skipped++;
            }
        }

        // Mẫu email riêng của tenant (một bản; chạy lại không tạo phiên bản mới)
        boolean hasTenantTemplate = templates.get(NotificationType.ORDER_PLACED, NotificationChannel.EMAIL)
                .map(t -> "TENANT".equals(t.source())).orElse(false);
        if (hasTenantTemplate) {
            skipped++;
        } else {
            templates.save(NotificationType.ORDER_PLACED, NotificationChannel.EMAIL,
                    "[{{tenant_name}}] Đơn hàng {{order_no}} đã được ghi nhận",
                    "Xin chào {{recipient_name}},\n\nĐơn hàng {{order_no}} (tổng {{grand_total}}) của bạn tại "
                            + "{{tenant_name}} đã được ghi nhận. Chúng tôi sẽ liên hệ khi xác nhận đơn.\n\nCảm ơn bạn.",
                    plan.staff().get(0).id());
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    private static Delivery build(UUID eventId, Spec spec, ReaderPlan reader, Instant createdAt) {
        String masked = AddressMasker.maskEmail(reader.email());
        boolean sent = spec.status() == DeliveryStatus.SENT;
        boolean dead = spec.status() == DeliveryStatus.DEAD;
        boolean skip = spec.status() == DeliveryStatus.SKIPPED;
        Instant finishedAt = createdAt.plus(Duration.ofSeconds(sent ? 8 : 90));
        return new Delivery(UUID.randomUUID(), eventId, spec.type().name(), spec.type(), NotificationChannel.EMAIL,
                reader.id(), reader.id().toString(), null, skip && spec.skip() == SkipReason.NO_ADDRESS ? null : masked,
                spec.params(), sent ? "DEFAULT" : null, sent ? 1 : null, spec.status(), skip ? spec.skip() : null,
                sent ? 1 : dead ? 5 : 0, 0, finishedAt, dead ? "SMTP_550_MAILBOX_UNAVAILABLE" : null,
                dead ? finishedAt : null, sent ? finishedAt : null, sent ? "demo-" + eventId.toString().substring(0, 8)
                : null, null, null, createdAt, finishedAt);
    }
}
