package lms.system.cms.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import lms.system.cms.application.AnonymizeExpiredContactRequestsUseCase;
import lms.system.cms.application.CmsPageRepositoryPort;
import lms.system.cms.application.ContactRequestRepositoryPort;
import lms.system.cms.application.ManageCmsPagesUseCase;
import lms.system.cms.application.ManageContactRequestsUseCase;
import lms.system.cms.application.MarkdownRendererPort;
import lms.system.cms.application.PublishedCmsPagesUseCase;
import lms.system.cms.application.SubmitContactRequestUseCase;
import lms.system.cms.infrastructure.markdown.CommonmarkMarkdownRenderer;
import lms.system.cms.infrastructure.scheduling.ContactAnonymizationJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition root của context cms (Clock do MenuBeanConfiguration cung cấp). Transaction do
 * {@code @Transactional} trên use case.
 */
@Configuration
public class CmsBeanConfiguration {

    /** Dự phòng nếu context menu không cấp Clock (chỉ một bean Clock được tạo). */
    @Bean
    @ConditionalOnMissingBean
    Clock cmsClock() {
        return Clock.systemUTC();
    }

    @Bean
    MarkdownRendererPort markdownRenderer() {
        return new CommonmarkMarkdownRenderer();
    }

    @Bean
    ManageCmsPagesUseCase manageCmsPagesUseCase(CmsPageRepositoryPort repository, Clock clock) {
        return new ManageCmsPagesUseCase(repository, clock);
    }

    @Bean
    PublishedCmsPagesUseCase publishedCmsPagesUseCase(CmsPageRepositoryPort repository, MarkdownRendererPort renderer) {
        return new PublishedCmsPagesUseCase(repository, renderer);
    }

    @Bean
    SubmitContactRequestUseCase submitContactRequestUseCase(ContactRequestRepositoryPort repository, Clock clock) {
        return new SubmitContactRequestUseCase(repository, clock);
    }

    @Bean
    ManageContactRequestsUseCase manageContactRequestsUseCase(ContactRequestRepositoryPort repository, Clock clock) {
        return new ManageContactRequestsUseCase(repository, clock);
    }

    /** Thời hạn lưu giữ cấp nền tảng (không theo tenant): lms.system.cms.contact-retention, mặc định 365 ngày. */
    @Bean
    AnonymizeExpiredContactRequestsUseCase anonymizeExpiredContactRequestsUseCase(
            ContactRequestRepositoryPort repository, Clock clock,
            @Value("${lms.system.cms.contact-retention:P365D}") Duration retention,
            @Value("${lms.system.cms.anonymization.batch-size:500}") int batchSize) {
        return new AnonymizeExpiredContactRequestsUseCase(repository, clock, retention, batchSize);
    }

    /** Job ẩn danh hằng ngày; tắt bằng lms.system.cms.anonymization.enabled=false. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "lms.system.cms.anonymization", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class AnonymizationScheduling {

        @Bean
        ContactAnonymizationJob contactAnonymizationJob(AnonymizeExpiredContactRequestsUseCase useCase) {
            return new ContactAnonymizationJob(useCase);
        }
    }
}
