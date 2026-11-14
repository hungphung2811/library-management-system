package lms.support.audit.infrastructure.config;

import lms.support.audit.infrastructure.persistence.SpringDataAuditLogRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Gắn Spring Data JPA với EntityManagerFactory và TransactionManager của AUDIT_DB do database-common
 * đăng ký (AUDIT_DB -> auditDbEntityManagerFactory / auditDbTransactionManager). Đặt ở @Configuration
 * riêng, không đặt trên lớp Application, để slice test (@WebMvcTest...) không bị kéo theo JPA.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = SpringDataAuditLogRepository.class,
        entityManagerFactoryRef = "auditDbEntityManagerFactory", transactionManagerRef = "auditDbTransactionManager")
public class AuditPersistenceConfiguration {
}
