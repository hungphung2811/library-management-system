package lms.support.notification.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lms.security.CurrentUser;
import lms.support.notification.application.NotificationSettingsUseCases;
import lms.support.notification.application.NotificationSettingsUseCases.ChannelSettingChange;
import lms.support.notification.application.NotificationSettingsUseCases.ChannelSettingView;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bật/tắt kênh theo loại thông báo của tenant. Khóa động NotificationSettingsController/getChannels và
 * NotificationSettingsController/updateChannels, mặc định chỉ admin tenant. Cặp chưa có dòng mặc định là bật.
 */
@RestController
@RequestMapping("/internal/notification-settings")
public class NotificationSettingsController {

    public record ChannelItem(@NotNull NotificationType type, @NotNull NotificationChannel channel,
                              @NotNull Boolean enabled) {
    }

    public record UpdateChannelsRequest(@NotNull @Valid List<@NotNull @Valid ChannelItem> items) {
    }

    private final NotificationSettingsUseCases settings;

    public NotificationSettingsController(NotificationSettingsUseCases settings) {
        this.settings = settings;
    }

    @GetMapping("/channels")
    public List<ChannelSettingView> getChannels() {
        return settings.channels();
    }

    @PutMapping("/channels")
    public List<ChannelSettingView> updateChannels(@Valid @RequestBody UpdateChannelsRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        return settings.updateChannels(request.items().stream()
                .map(i -> new ChannelSettingChange(i.type(), i.channel(), i.enabled())).toList(),
                CurrentUser.from(jwt).userId());
    }
}
