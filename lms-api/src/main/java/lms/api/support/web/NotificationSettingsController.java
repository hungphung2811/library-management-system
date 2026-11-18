package lms.api.support.web;

import jakarta.validation.Valid;
import java.util.List;
import lms.api.support.client.NotificationAdminClient;
import lms.api.support.dto.NotificationAdminDtos.ChannelSettingView;
import lms.api.support.dto.NotificationAdminDtos.UpdateChannelsRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE (ADR-0030 D7): bật/tắt kênh theo loại thông báo của tenant. Quyền do support-service kiểm. */
@RestController
@RequestMapping("/api/notification-settings")
public class NotificationSettingsController {

    private final NotificationAdminClient notifications;

    public NotificationSettingsController(NotificationAdminClient notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/channels")
    public List<ChannelSettingView> channels() {
        return notifications.channelSettings();
    }

    @PutMapping("/channels")
    public List<ChannelSettingView> updateChannels(@Valid @RequestBody UpdateChannelsRequest request) {
        return notifications.updateChannelSettings(request);
    }
}
