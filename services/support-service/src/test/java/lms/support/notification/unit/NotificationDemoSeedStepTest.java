package lms.support.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep.SeedResult;
import lms.support.notification.application.DeliveryPort;
import lms.support.notification.application.NotificationTemplateUseCases;
import lms.support.notification.application.SaveNotificationPort;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.Notification;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.infrastructure.demo.NotificationDemoSeedStep;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Bước seed thông báo (ADR-0018): chỉ trạng thái kết thúc (không có gì để dispatcher gửi), chạy lại không nhân đôi. */
class NotificationDemoSeedStepTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private final Clock clock = Clock.system(VN);
    private final List<Delivery> deliveries = new ArrayList<>();
    private final List<Notification> notifications = new ArrayList<>();
    private final Set<UUID> deliveryEvents = new HashSet<>();
    private final Set<UUID> notificationEvents = new HashSet<>();
    private final NotificationTemplateUseCases templates = mock(NotificationTemplateUseCases.class);

    private NotificationDemoSeedStep step() {
        DeliveryPort deliveryPort = mock(DeliveryPort.class);
        when(deliveryPort.insertIfAbsent(any())).thenAnswer(i -> {
            Delivery d = i.getArgument(0);
            boolean isNew = deliveryEvents.add(d.eventId());
            if (isNew) deliveries.add(d);
            return isNew;
        });
        SaveNotificationPort notificationPort = n -> {
            boolean isNew = notificationEvents.add(n.eventId());
            if (isNew) notifications.add(n);
            return isNew;
        };
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new NotificationDemoSeedStep(deliveryPort, notificationPort, templates, manager);
    }

    private DemoSeedContext context() {
        return new DemoSeedContext(TENANT, DemoPlan.generate(LocalDate.now(VN)), "x", clock);
    }

    @Test
    void chiTaoTrangThaiKetThucDuTrangThaiVaIdempotent() {
        when(templates.get(NotificationType.ORDER_PLACED, NotificationChannel.EMAIL)).thenReturn(Optional.empty());
        NotificationDemoSeedStep step = step();

        SeedResult first = step.run(context());
        assertThat(deliveries).isNotEmpty();
        assertThat(deliveries).extracting(Delivery::status).containsOnly(DeliveryStatus.SENT, DeliveryStatus.DEAD,
                DeliveryStatus.SKIPPED).contains(DeliveryStatus.SENT, DeliveryStatus.DEAD, DeliveryStatus.SKIPPED);
        // địa chỉ đầy đủ không được lưu, kênh duy nhất là EMAIL
        assertThat(deliveries).allSatisfy(d -> {
            assertThat(d.recipientAddress()).isNull();
            assertThat(d.channel()).isEqualTo(NotificationChannel.EMAIL);
            assertThat(d.createdAt()).isBeforeOrEqualTo(clock.instant());
        });
        assertThat(notifications).extracting(Notification::isRead).contains(true, false);
        assertThat(first.created()).isEqualTo(deliveries.size() + notifications.size() + 1);

        int deliveryCount = deliveries.size();
        SeedResult second = step.run(context());
        assertThat(deliveries).hasSize(deliveryCount);
        assertThat(second.created()).isEqualTo(1); // mẫu riêng chưa có nên bước vẫn gọi save (mock), còn lại bỏ qua
        verify(templates, never()).get(NotificationType.ORDER_CANCELLED, NotificationChannel.EMAIL);
    }
}
