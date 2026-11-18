package lms.support.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.application.NotificationConfigPorts.ChannelSetting;
import lms.support.notification.application.NotificationConfigPorts.ChannelSettingsPort;
import lms.support.notification.application.NotificationConfigPorts.Preference;
import lms.support.notification.application.NotificationConfigPorts.PreferencesPort;
import lms.support.notification.application.NotificationConfigPorts.StoredTemplate;
import lms.support.notification.application.NotificationConfigPorts.TemplateStorePort;
import lms.support.notification.application.NotificationConfigPorts.TenantRegistryPort;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;

/** Adapter JPA cho các cổng cấu hình thông báo (AUDIT_DB): mẫu, kênh, tùy chọn, danh sách tenant. */
public class NotificationConfigPersistenceAdapter
        implements TemplateStorePort, ChannelSettingsPort, PreferencesPort, TenantRegistryPort {

    private final SpringDataTemplateRepository templates;
    private final SpringDataChannelSettingRepository channels;
    private final SpringDataPreferenceRepository preferences;
    private final SpringDataTenantRegistryRepository registry;

    public NotificationConfigPersistenceAdapter(SpringDataTemplateRepository templates,
                                                SpringDataChannelSettingRepository channels,
                                                SpringDataPreferenceRepository preferences,
                                                SpringDataTenantRegistryRepository registry) {
        this.templates = templates;
        this.channels = channels;
        this.preferences = preferences;
        this.registry = registry;
    }

    // ---- mẫu ----

    @Override
    public Optional<MessageTemplate> findActive(NotificationType type, NotificationChannel channel) {
        return templates.findFirstByTypeAndChannelAndActiveTrue(type, channel).map(this::toTemplate);
    }

    @Override
    public List<StoredTemplate> listActive() {
        return templates.findByActiveTrue().stream().map(e -> new StoredTemplate(e.getType(), e.getChannel(),
                toTemplate(e), e.getCreatedAt(), e.getCreatedBy())).toList();
    }

    @Override
    public MessageTemplate saveNewVersion(NotificationType type, NotificationChannel channel, String subject,
                                          String body, UUID by, Instant now) {
        int next = templates.maxVersion(type, channel) + 1;
        templates.deactivate(type, channel);
        templates.save(new NotificationTemplateJpaEntity(UUID.randomUUID(), type, channel, next, subject, body, by, now));
        return new MessageTemplate(subject, body, MessageTemplate.TENANT, next);
    }

    private MessageTemplate toTemplate(NotificationTemplateJpaEntity e) {
        return new MessageTemplate(e.getSubject(), e.getBody(), MessageTemplate.TENANT, e.getVersion());
    }

    // ---- kênh theo tenant ----

    @Override
    public boolean isEnabled(NotificationType type, NotificationChannel channel) {
        return channels.findById(new NotificationChannelSettingJpaEntity.Key(type, channel))
                .map(NotificationChannelSettingJpaEntity::isEnabled).orElse(true);
    }

    @Override
    public List<ChannelSetting> listExplicit() {
        return channels.findAll().stream().map(e -> new ChannelSetting(e.getType(), e.getChannel(), e.isEnabled()))
                .toList();
    }

    @Override
    public void upsert(NotificationType type, NotificationChannel channel, boolean enabled, UUID by, Instant now) {
        NotificationChannelSettingJpaEntity.Key key = new NotificationChannelSettingJpaEntity.Key(type, channel);
        Optional<NotificationChannelSettingJpaEntity> existing = channels.findById(key);
        if (existing.isPresent()) {
            existing.get().update(enabled, by, now);
        } else {
            channels.save(new NotificationChannelSettingJpaEntity(type, channel, enabled, by, now));
        }
    }

    // ---- tùy chọn người dùng ----

    @Override
    public boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel) {
        return preferences.findById(new NotificationPreferenceJpaEntity.Key(userId, category, channel))
                .map(NotificationPreferenceJpaEntity::isEnabled).orElse(true);
    }

    @Override
    public List<Preference> listExplicit(UUID userId) {
        return preferences.findByUserId(userId).stream()
                .map(e -> new Preference(e.getCategory(), e.getChannel(), e.isEnabled())).toList();
    }

    @Override
    public void upsert(UUID userId, NotificationCategory category, NotificationChannel channel, boolean enabled,
                       Instant now) {
        NotificationPreferenceJpaEntity.Key key = new NotificationPreferenceJpaEntity.Key(userId, category, channel);
        Optional<NotificationPreferenceJpaEntity> existing = preferences.findById(key);
        if (existing.isPresent()) {
            existing.get().update(enabled, now);
        } else {
            preferences.save(new NotificationPreferenceJpaEntity(userId, category, channel, enabled, now));
        }
    }

    // ---- danh sách tenant ----

    @Override
    public void registerIfAbsent(UUID tenantId, Instant now) {
        registry.insertIfAbsent(tenantId, now);
    }

    @Override
    public List<UUID> allTenantIds() {
        return registry.findAll().stream().map(NotificationTenantJpaEntity::getTenantId).toList();
    }
}
