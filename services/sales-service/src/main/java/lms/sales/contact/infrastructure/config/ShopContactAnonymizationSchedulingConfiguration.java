package lms.sales.contact.infrastructure.config;

import lms.sales.contact.application.AnonymizeExpiredShopContactsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật job khi {@code lms.sales.anonymize.enabled} (mặc định true), cùng cờ với job khách vãng lai. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lms.sales.anonymize.enabled", havingValue = "true", matchIfMissing = true)
public class ShopContactAnonymizationSchedulingConfiguration {

    @Bean
    ShopContactAnonymizationJob shopContactAnonymizationJob(TenantDirectoryPort tenants,
                                                            AnonymizeExpiredShopContactsUseCase useCase) {
        return new ShopContactAnonymizationJob(tenants, useCase);
    }
}
