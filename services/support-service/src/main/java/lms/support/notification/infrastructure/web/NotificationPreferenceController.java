package lms.support.notification.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import lms.support.notification.application.DeliveryUseCases;
import lms.support.notification.application.DeliveryUseCases.MyDeliveryView;
import lms.support.notification.application.NotificationSettingsUseCases;
import lms.support.notification.application.NotificationSettingsUseCases.PreferenceChange;
import lms.support.notification.application.NotificationSettingsUseCases.PreferenceView;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tùy chọn nhận thông báo và lịch sử gửi của chính người đang đăng nhập (ADR-0030 D7): userId lấy từ token, tenant từ
 * TenantContext. Chỉ cần đăng nhập, không cần khóa quyền. Địa chỉ trong lịch sử luôn ở dạng đã che.
 */
@RestController
@RequestMapping("/internal/notifications/me")
public class NotificationPreferenceController {

    public record PreferenceItem(@NotNull NotificationCategory category, @NotNull NotificationChannel channel,
                                 @NotNull Boolean enabled) {
    }

    public record UpdatePreferencesRequest(@NotNull @Valid List<@NotNull @Valid PreferenceItem> items) {
    }

    private final NotificationSettingsUseCases settings;
    private final DeliveryUseCases deliveries;

    public NotificationPreferenceController(NotificationSettingsUseCases settings, DeliveryUseCases deliveries) {
        this.settings = settings;
        this.deliveries = deliveries;
    }

    @AuthenticatedOnly
    @GetMapping("/preferences")
    public List<PreferenceView> preferences(@AuthenticationPrincipal Jwt jwt) {
        return settings.preferences(CurrentUser.from(jwt).userId());
    }

    @AuthenticatedOnly
    @PutMapping("/preferences")
    public List<PreferenceView> updatePreferences(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody UpdatePreferencesRequest request) {
        return settings.updatePreferences(CurrentUser.from(jwt).userId(), request.items().stream()
                .map(i -> new PreferenceChange(i.category(), i.channel(), i.enabled())).toList());
    }

    @AuthenticatedOnly
    @GetMapping("/deliveries")
    public List<MyDeliveryView> deliveries(@AuthenticationPrincipal Jwt jwt) {
        return deliveries.mine(CurrentUser.from(jwt).userId());
    }
}
