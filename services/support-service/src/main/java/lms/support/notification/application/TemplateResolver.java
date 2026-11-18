package lms.support.notification.application;

import java.util.Map;
import java.util.Optional;
import lms.support.notification.application.NotificationConfigPorts.DefaultTemplatePort;
import lms.support.notification.application.NotificationConfigPorts.TemplateStorePort;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.TemplateRenderer;

/** Chọn mẫu của loại+kênh: bản ghi đè active của tenant hiện tại, không có thì mặc định trong classpath. */
public class TemplateResolver {

    private final TemplateStorePort tenantTemplates;
    private final DefaultTemplatePort defaults;

    public TemplateResolver(TemplateStorePort tenantTemplates, DefaultTemplatePort defaults) {
        this.tenantTemplates = tenantTemplates;
        this.defaults = defaults;
    }

    public Optional<MessageTemplate> resolve(NotificationType type, NotificationChannel channel) {
        Optional<MessageTemplate> override = tenantTemplates.findActive(type, channel);
        return override.isPresent() ? override : defaults.find(type, channel);
    }

    /** Tiêu đề đã dựng, dùng làm nội dung thông báo trong ứng dụng cho các loại không có câu chữ riêng. */
    public Optional<String> renderSubject(NotificationType type, Map<String, String> values) {
        return resolve(type, NotificationChannel.EMAIL).map(t -> TemplateRenderer.render(t, values).subject());
    }
}
