package lms.support.notification.infrastructure.web;

import java.util.List;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.application.NotificationResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Người dùng chỉ xem thông báo của chính mình: userId lấy từ token, tenant từ TenantContext. */
@RestController
@RequestMapping("/internal/notifications")
public class NotificationController {

    private final ListMyNotificationsUseCase listMine;

    public NotificationController(ListMyNotificationsUseCase listMine) {
        this.listMine = listMine;
    }

    @AuthenticatedOnly
    @GetMapping("/me")
    public List<NotificationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return listMine.execute(CurrentUser.from(jwt).userId());
    }
}
