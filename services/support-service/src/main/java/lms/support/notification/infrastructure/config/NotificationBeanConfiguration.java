package lms.support.notification.infrastructure.config;

import java.time.Clock;
import lms.support.notification.application.CreateNotificationUseCase;
import lms.support.notification.application.ListMyNotificationsUseCase;
import lms.support.notification.infrastructure.persistence.NotificationPersistenceAdapter;
import lms.support.notification.infrastructure.persistence.SpringDataNotificationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của ngữ cảnh notification (Clock dùng chung từ AuditBeanConfiguration). */
@Configuration
public class NotificationBeanConfiguration {

    @Bean
    NotificationPersistenceAdapter notificationPersistenceAdapter(SpringDataNotificationRepository jpa) {
        return new NotificationPersistenceAdapter(jpa);
    }

    @Bean
    CreateNotificationUseCase createNotificationUseCase(NotificationPersistenceAdapter persistence, Clock clock) {
        return new CreateNotificationUseCase(persistence, clock);
    }

    @Bean
    ListMyNotificationsUseCase listMyNotificationsUseCase(NotificationPersistenceAdapter persistence) {
        return new ListMyNotificationsUseCase(persistence);
    }
}
