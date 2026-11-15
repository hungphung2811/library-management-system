package lms.api.support.web;

import java.util.List;
import lms.api.support.client.SupportClient;
import lms.api.support.dto.NotificationView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API cho FE: thông báo của người đang đăng nhập (support-service lọc theo token và tenant). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final SupportClient support;

    public NotificationController(SupportClient support) {
        this.support = support;
    }

    @GetMapping("/me")
    public List<NotificationView> mine() {
        return support.myNotifications();
    }
}
