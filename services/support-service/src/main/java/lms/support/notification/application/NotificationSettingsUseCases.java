package lms.support.notification.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.support.notification.application.NotificationConfigPorts.ChannelSetting;
import lms.support.notification.application.NotificationConfigPorts.ChannelSettingsPort;
import lms.support.notification.application.NotificationConfigPorts.Preference;
import lms.support.notification.application.NotificationConfigPorts.PreferencesPort;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.PreferenceMandatoryException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cấu hình kênh theo tenant (D7: không có dòng = bật) và tùy chọn nhận của người dùng theo nhóm (không có dòng = nhận;
 * nhóm bắt buộc ACCOUNT, REFUND không tắt được). Thông báo trong ứng dụng luôn ghi, không nằm trong các cấu hình này.
 */
public class NotificationSettingsUseCases {

    public record ChannelSettingView(String type, String category, String channel, boolean enabled) {
    }

    public record ChannelSettingChange(NotificationType type, NotificationChannel channel, boolean enabled) {
    }

    public record PreferenceView(String category, String channel, boolean enabled, boolean mandatory) {
    }

    public record PreferenceChange(NotificationCategory category, NotificationChannel channel, boolean enabled) {
    }

    private final ChannelSettingsPort channels;
    private final PreferencesPort preferences;
    private final Clock clock;

    public NotificationSettingsUseCases(ChannelSettingsPort channels, PreferencesPort preferences, Clock clock) {
        this.channels = channels;
        this.preferences = preferences;
        this.clock = clock;
    }

    /** Đủ mọi (loại x kênh), giá trị mặc định cho cặp chưa có dòng. */
    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public List<ChannelSettingView> channels() {
        Map<String, Boolean> explicit = new HashMap<>();
        for (ChannelSetting s : channels.listExplicit()) explicit.put(s.type() + ":" + s.channel(), s.enabled());
        List<ChannelSettingView> out = new ArrayList<>();
        for (NotificationType t : NotificationType.values()) {
            for (NotificationChannel c : NotificationChannel.values()) {
                out.add(new ChannelSettingView(t.name(), t.category().name(), c.name(),
                        explicit.getOrDefault(t + ":" + c, true)));
            }
        }
        return out;
    }

    @Transactional(NotificationTransactions.MANAGER)
    public List<ChannelSettingView> updateChannels(List<ChannelSettingChange> changes, UUID by) {
        for (ChannelSettingChange c : changes) {
            channels.upsert(c.type(), c.channel(), c.enabled(), by, clock.instant());
        }
        return channels();
    }

    @Transactional(value = NotificationTransactions.MANAGER, readOnly = true)
    public List<PreferenceView> preferences(UUID userId) {
        Map<String, Boolean> explicit = new HashMap<>();
        for (Preference p : preferences.listExplicit(userId)) explicit.put(p.category() + ":" + p.channel(), p.enabled());
        List<PreferenceView> out = new ArrayList<>();
        for (NotificationCategory cat : NotificationCategory.values()) {
            for (NotificationChannel ch : NotificationChannel.values()) {
                // Nhóm bắt buộc luôn nhận, bất kể dòng cũ nếu có
                boolean enabled = cat.mandatory() || explicit.getOrDefault(cat + ":" + ch, true);
                out.add(new PreferenceView(cat.name(), ch.name(), enabled, cat.mandatory()));
            }
        }
        return out;
    }

    /** Tắt nhóm bắt buộc thì cả yêu cầu bị từ chối (400 PREFERENCE_MANDATORY), không ghi gì. */
    @Transactional(NotificationTransactions.MANAGER)
    public List<PreferenceView> updatePreferences(UUID userId, List<PreferenceChange> changes) {
        for (PreferenceChange c : changes) {
            if (c.category().mandatory() && !c.enabled()) throw new PreferenceMandatoryException(c.category());
        }
        for (PreferenceChange c : changes) {
            if (!c.category().mandatory()) {
                preferences.upsert(userId, c.category(), c.channel(), c.enabled(), clock.instant());
            }
        }
        return preferences(userId);
    }
}
