package lms.support.notification.infrastructure.config;

import lms.support.notification.infrastructure.persistence.SpringDataNotificationRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Gắn repository thông báo với EntityManagerFactory/TransactionManager của AUDIT_DB (dùng chung với audit). */
@Configuration
@EnableJpaRepositories(basePackageClasses = SpringDataNotificationRepository.class,
        entityManagerFactoryRef = "auditDbEntityManagerFactory", transactionManagerRef = "auditDbTransactionManager")
public class NotificationPersistenceConfiguration {
}
