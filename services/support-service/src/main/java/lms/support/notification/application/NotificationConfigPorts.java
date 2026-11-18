package lms.support.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;

/** Gom các cổng cấu hình nhỏ của context notification (đều theo tenant hiện tại, trừ TenantRegistry và DefaultTemplates). */
public final class NotificationConfigPorts {

    private NotificationConfigPorts() {
    }

    /** Danh sách tenant đã từng có thông báo (bảng không RLS) để dispatcher lặp. */
    public interface TenantRegistryPort {
        void registerIfAbsent(UUID tenantId, Instant now);

        List<UUID> allTenantIds();
    }

    /** Mẫu mặc định trong classpath, có phiên bản. */
    public interface DefaultTemplatePort {
        Optional<MessageTemplate> find(NotificationType type, NotificationChannel channel);
    }

    /** Mẫu ghi đè của tenant. */
    public interface TemplateStorePort {
        Optional<MessageTemplate> findActive(NotificationType type, NotificationChannel channel);

        List<StoredTemplate> listActive();

        /** Lưu phiên bản mới (version = lớn nhất + 1), bản cũ thôi active; trả phiên bản mới. */
        MessageTemplate saveNewVersion(NotificationType type, NotificationChannel channel, String subject, String body,
                                       UUID by, Instant now);
    }

    public record StoredTemplate(NotificationType type, NotificationChannel channel, MessageTemplate template, Instant createdAt,
                          UUID createdBy) {
    }

    /** Bật/tắt kênh theo loại của tenant; không có dòng = mặc định bật. */
    public interface ChannelSettingsPort {
        boolean isEnabled(NotificationType type, NotificationChannel channel);

        List<ChannelSetting> listExplicit();

        void upsert(NotificationType type, NotificationChannel channel, boolean enabled, UUID by, Instant now);
    }

    public record ChannelSetting(NotificationType type, NotificationChannel channel, boolean enabled) {
    }

    /** Tùy chọn nhận của người dùng; không có dòng = nhận. */
    public interface PreferencesPort {
        boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel);

        List<Preference> listExplicit(UUID userId);

        void upsert(UUID userId, NotificationCategory category, NotificationChannel channel, boolean enabled,
                    Instant now);
    }

    public record Preference(NotificationCategory category, NotificationChannel channel, boolean enabled) {
    }
}
