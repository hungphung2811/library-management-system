package lms.support.notification.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lms.security.CurrentUser;
import lms.support.notification.application.NotificationTemplateUseCases;
import lms.support.notification.application.NotificationTemplateUseCases.PreviewView;
import lms.support.notification.application.NotificationTemplateUseCases.TemplateView;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mẫu nội dung thông báo (ADR-0030 D6). Khóa động NotificationTemplateController/list|get|save|preview, mặc định chỉ
 * admin tenant. Mỗi lần lưu là một phiên bản mới; tham số ngoài danh sách của loại bị từ chối (400
 * TEMPLATE_PARAM_NOT_ALLOWED).
 */
@RestController
@RequestMapping("/internal/notification-templates")
public class NotificationTemplateController {

    public record SaveTemplateRequest(@NotBlank @Size(max = 200) String subject,
                                      @NotBlank @Size(max = 5000) String body) {
    }

    /** subject và body cùng có thì dựng thử bản nháp, cùng vắng thì dựng thử mẫu đang hiệu lực. */
    public record PreviewRequest(@Size(max = 200) String subject, @Size(max = 5000) String body) {
    }

    private final NotificationTemplateUseCases templates;

    public NotificationTemplateController(NotificationTemplateUseCases templates) {
        this.templates = templates;
    }

    @GetMapping
    public List<TemplateView> list() {
        return templates.list();
    }

    @GetMapping("/{type}/{channel}")
    public ResponseEntity<TemplateView> get(@PathVariable NotificationType type,
                                            @PathVariable NotificationChannel channel) {
        return templates.get(type, channel).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{type}/{channel}")
    public TemplateView save(@PathVariable NotificationType type, @PathVariable NotificationChannel channel,
                             @Valid @RequestBody SaveTemplateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return templates.save(type, channel, request.subject(), request.body(), CurrentUser.from(jwt).userId());
    }

    @PostMapping("/{type}/{channel}/preview")
    public PreviewView preview(@PathVariable NotificationType type, @PathVariable NotificationChannel channel,
                               @Valid @RequestBody PreviewRequest request) {
        return templates.preview(type, channel, request.subject(), request.body());
    }
}
