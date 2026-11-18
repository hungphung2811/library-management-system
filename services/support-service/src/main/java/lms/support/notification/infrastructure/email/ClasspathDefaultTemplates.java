package lms.support.notification.infrastructure.email;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lms.support.notification.application.NotificationConfigPorts.DefaultTemplatePort;
import lms.support.notification.domain.MessageTemplate;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mẫu mặc định trong classpath: {@code notification-templates/vi/<LOẠI>/<kênh>.v<n>.txt}, dòng đầu là tiêu đề, phần còn
 * lại là thân (ADR-0030 D6). Phiên bản mặc định là hằng số trong {@link #DEFAULT_VERSION}: đổi nội dung = thêm file
 * {@code v<n+1>} rồi tăng hằng số, không sửa file cũ (delivery cũ vẫn truy được nội dung đã gửi).
 */
public class ClasspathDefaultTemplates implements DefaultTemplatePort {

    /** Phiên bản mặc định hiện hành của từng loại (mặc định 1). */
    static final Map<NotificationType, Integer> DEFAULT_VERSION = Map.of();

    private static final Logger log = LoggerFactory.getLogger(ClasspathDefaultTemplates.class);
    private static final String ROOT = "notification-templates/vi/";

    private final Map<String, Optional<MessageTemplate>> cache = new ConcurrentHashMap<>();

    @Override
    public Optional<MessageTemplate> find(NotificationType type, NotificationChannel channel) {
        return cache.computeIfAbsent(type + ":" + channel, k -> load(type, channel));
    }

    static int versionOf(NotificationType type) {
        return DEFAULT_VERSION.getOrDefault(type, 1);
    }

    private static Optional<MessageTemplate> load(NotificationType type, NotificationChannel channel) {
        int version = versionOf(type);
        String path = ROOT + type.name() + "/" + channel.name().toLowerCase(java.util.Locale.ROOT) + ".v" + version
                + ".txt";
        try (InputStream in = ClasspathDefaultTemplates.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                log.warn("Thiếu mẫu mặc định {}", path);
                return Optional.empty();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            int nl = text.indexOf('\n');
            if (nl < 0) return Optional.empty();
            String subject = text.substring(0, nl).trim();
            String body = text.substring(nl + 1).trim();
            return Optional.of(new MessageTemplate(subject, body, MessageTemplate.DEFAULT, version));
        } catch (IOException | RuntimeException e) {
            log.warn("Không đọc được mẫu mặc định {}: {}", path, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
