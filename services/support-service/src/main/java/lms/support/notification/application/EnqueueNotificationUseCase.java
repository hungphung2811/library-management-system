package lms.support.notification.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lms.support.notification.application.NotificationConfigPorts.ChannelSettingsPort;
import lms.support.notification.application.NotificationConfigPorts.PreferencesPort;
import lms.support.notification.application.NotificationConfigPorts.TenantRegistryPort;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.Notification;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.SkipReason;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xếp thông báo vào hàng chờ trong MỘT transaction audit_db (ADR-0030 D4): ghi thông báo trong ứng dụng (nếu người nhận
 * có tài khoản) và chèn delivery cho từng kênh ngoài. Idempotent theo khóa khử trùng: Kafka giao lại hoặc hai instance
 * cùng nhận chỉ ra một delivery. Quyết định gửi theo thứ tự kênh có provider, tenant bật, người dùng không tắt (nhóm
 * bắt buộc thì bỏ qua bước này); không đạt thì delivery SKIPPED kèm lý do để lịch sử giải thích được. Địa chỉ và
 * tình trạng tài khoản được xét lúc gửi (dispatcher).
 */
public class EnqueueNotificationUseCase {

    private final SaveNotificationPort inApp;
    private final DeliveryPort deliveries;
    private final TenantRegistryPort tenants;
    private final ChannelSettingsPort channelSettings;
    private final PreferencesPort preferences;
    private final NotificationProviderRegistry providers;
    private final TemplateResolver templates;
    private final Clock clock;

    public EnqueueNotificationUseCase(SaveNotificationPort inApp, DeliveryPort deliveries, TenantRegistryPort tenants,
                                      ChannelSettingsPort channelSettings, PreferencesPort preferences,
                                      NotificationProviderRegistry providers, TemplateResolver templates, Clock clock) {
        this.inApp = inApp;
        this.deliveries = deliveries;
        this.tenants = tenants;
        this.channelSettings = channelSettings;
        this.preferences = preferences;
        this.providers = providers;
        this.templates = templates;
        this.clock = clock;
    }

    /** @return số delivery chèn mới (0 khi sự kiện đã được xử lý trước đó) */
    @Transactional(NotificationTransactions.MANAGER)
    public int execute(NotificationTrigger trigger) {
        UUID tenantId = TenantContext.require();
        Instant now = clock.instant();
        tenants.registerIfAbsent(tenantId, now);
        if (trigger.recipientUserId() != null) {
            writeInApp(trigger, now);
        }
        int inserted = 0;
        for (NotificationChannel channel : NotificationChannel.values()) {
            Delivery delivery = trigger.recipientUserId() != null
                    ? Delivery.pendingForUser(trigger.eventId(), trigger.eventType(), trigger.type(), channel,
                    trigger.recipientUserId(), trigger.params(), now)
                    : Delivery.pendingForAddress(trigger.eventId(), trigger.eventType(), trigger.type(), channel,
                    trigger.directEmail(), trigger.params(), now);
            SkipReason skip = skipReason(trigger, channel);
            if (skip != null) {
                delivery.skippedAtEnqueue(skip, now);
            }
            if (deliveries.insertIfAbsent(delivery)) {
                inserted++;
            }
        }
        return inserted;
    }

    private SkipReason skipReason(NotificationTrigger trigger, NotificationChannel channel) {
        if (!providers.has(channel)) return SkipReason.NO_PROVIDER;
        if (!channelSettings.isEnabled(trigger.type(), channel)) return SkipReason.CHANNEL_DISABLED;
        if (trigger.recipientUserId() != null && !trigger.type().category().mandatory()
                && !preferences.isEnabled(trigger.recipientUserId(), trigger.type().category(), channel)) {
            return SkipReason.OPTED_OUT;
        }
        return null;
    }

    private void writeInApp(NotificationTrigger trigger, Instant now) {
        String message = trigger.inAppMessage();
        if (message == null || message.isBlank()) {
            Map<String, String> values = new HashMap<>(trigger.params());
            message = templates.renderSubject(trigger.type(), values).orElse(null);
        }
        if (message == null || message.isBlank()) return;
        UUID inAppId = trigger.inAppEventId() != null ? trigger.inAppEventId() : UUID.nameUUIDFromBytes(
                ("inapp:" + trigger.eventId() + ":" + trigger.recipientUserId()).getBytes(StandardCharsets.UTF_8));
        inApp.saveIfAbsent(Notification.create(inAppId, trigger.recipientUserId(), trigger.type().name(), message, now));
    }
}
