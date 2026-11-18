package lms.support.notification.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật @Scheduled cho job xả hàng chờ thông báo; tắt cùng lms.notification.dispatch.enabled=false. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "lms.notification.dispatch", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationSchedulingConfiguration {
}
