package lms.sales.ordering.infrastructure.config;

import lms.sales.ordering.application.AnonymizeWalkInContactsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật job ẩn danh khi {@code lms.sales.anonymize.enabled} (mặc định true); false thì không có lịch chạy. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lms.sales.anonymize.enabled", havingValue = "true", matchIfMissing = true)
public class AnonymizeWalkInSchedulingConfiguration {

    @Bean
    AnonymizeWalkInJob anonymizeWalkInJob(TenantDirectoryPort tenants, AnonymizeWalkInContactsUseCase useCase) {
        return new AnonymizeWalkInJob(tenants, useCase);
    }
}
