package lms.api.support.web;

import jakarta.validation.Valid;
import java.util.List;
import lms.api.support.client.NotificationAdminClient;
import lms.api.support.dto.NotificationAdminDtos.PreviewTemplateRequest;
import lms.api.support.dto.NotificationAdminDtos.SaveTemplateRequest;
import lms.api.support.dto.NotificationAdminDtos.TemplatePreviewView;
import lms.api.support.dto.NotificationAdminDtos.TemplateView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API cho FE (ADR-0030 D6): mẫu nội dung thông báo (mặc định + ghi đè của tenant). Mỗi lần lưu là một phiên bản mới;
 * tham số ngoài danh sách cho phép của loại bị từ chối (400 TEMPLATE_PARAM_NOT_ALLOWED). Quyền do support-service kiểm.
 */
@RestController
@RequestMapping("/api/notification-templates")
public class NotificationTemplateController {

    private final NotificationAdminClient notifications;

    public NotificationTemplateController(NotificationAdminClient notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<TemplateView> list() {
        return notifications.templates();
    }

    @GetMapping("/{type}/{channel}")
    public TemplateView get(@PathVariable String type, @PathVariable String channel) {
        return notifications.template(type, channel);
    }

    @PutMapping("/{type}/{channel}")
    public TemplateView save(@PathVariable String type, @PathVariable String channel,
                             @Valid @RequestBody SaveTemplateRequest request) {
        return notifications.saveTemplate(type, channel, request);
    }

    @PostMapping("/{type}/{channel}/preview")
    public TemplatePreviewView preview(@PathVariable String type, @PathVariable String channel,
                                       @Valid @RequestBody PreviewTemplateRequest request) {
        return notifications.previewTemplate(type, channel, request);
    }
}
