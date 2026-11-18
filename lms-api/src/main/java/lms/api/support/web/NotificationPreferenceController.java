package lms.api.support.web;

import jakarta.validation.Valid;
import java.util.List;
import lms.api.support.client.NotificationAdminClient;
import lms.api.support.dto.NotificationAdminDtos.MyDeliveryView;
import lms.api.support.dto.NotificationAdminDtos.PreferenceView;
import lms.api.support.dto.NotificationAdminDtos.UpdatePreferencesRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API cho FE (ADR-0030 D7): tùy chọn nhận thông báo theo nhóm/kênh và lịch sử gửi của chính người đang đăng nhập.
 * Chỉ cần đăng nhập; support-service lấy userId từ token. Nhóm bắt buộc (ACCOUNT, REFUND) không tắt được (400
 * PREFERENCE_MANDATORY).
 */
@RestController
@RequestMapping("/api/notifications/me")
public class NotificationPreferenceController {

    private final NotificationAdminClient notifications;

    public NotificationPreferenceController(NotificationAdminClient notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/preferences")
    public List<PreferenceView> preferences() {
        return notifications.myPreferences();
    }

    @PutMapping("/preferences")
    public List<PreferenceView> updatePreferences(@Valid @RequestBody UpdatePreferencesRequest request) {
        return notifications.updateMyPreferences(request);
    }

    @GetMapping("/deliveries")
    public List<MyDeliveryView> deliveries() {
        return notifications.myDeliveries();
    }
}
