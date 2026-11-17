package lms.sales.contact.infrastructure.config;

import java.time.Clock;
import java.time.Duration;
import lms.sales.contact.application.AnonymizeExpiredShopContactsUseCase;
import lms.sales.contact.application.AnonymizeShopContactBatchUseCase;
import lms.sales.contact.application.ShopContactQueryUseCases;
import lms.sales.contact.application.ShopContactRepository;
import lms.sales.contact.application.ShopContactWorkflowUseCases;
import lms.sales.contact.application.SubmitShopContactUseCase;
import lms.sales.contact.domain.ContactSubmissionPolicy;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.TenantDirectoryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context contact. Cấu hình chống spam là cấu hình nền tảng (lms.sales.contact.*). */
@Configuration
public class ContactBeanConfiguration {

    @Bean
    ContactSubmissionPolicy contactSubmissionPolicy(
            @Value("${lms.sales.contact.max-per-user-per-day:5}") int maxPerUserPerDay,
            @Value("${lms.sales.contact.duplicate-window:PT10M}") Duration duplicateWindow) {
        return new ContactSubmissionPolicy(maxPerUserPerDay, duplicateWindow);
    }

    @Bean
    SubmitShopContactUseCase submitShopContactUseCase(ShopContactRepository repository,
                                                      ContactSubmissionPolicy policy, TenantDirectoryPort tenants,
                                                      SalesAuditPort audit, Clock clock) {
        return new SubmitShopContactUseCase(repository, policy, tenants, audit, clock);
    }

    @Bean
    ShopContactQueryUseCases shopContactQueryUseCases(ShopContactRepository repository) {
        return new ShopContactQueryUseCases(repository);
    }

    @Bean
    ShopContactWorkflowUseCases shopContactWorkflowUseCases(ShopContactRepository repository, SalesAuditPort audit,
                                                            Clock clock) {
        return new ShopContactWorkflowUseCases(repository, audit, clock);
    }

    @Bean
    AnonymizeShopContactBatchUseCase anonymizeShopContactBatchUseCase(ShopContactRepository repository,
                                                                      SalesAuditPort audit) {
        return new AnonymizeShopContactBatchUseCase(repository, audit);
    }

    @Bean
    AnonymizeExpiredShopContactsUseCase anonymizeExpiredShopContactsUseCase(GetSalesSettingsUseCase settings,
                                                                            AnonymizeShopContactBatchUseCase batch,
                                                                            Clock clock) {
        return new AnonymizeExpiredShopContactsUseCase(settings, batch, clock,
                AnonymizeExpiredShopContactsUseCase.DEFAULT_BATCH_SIZE);
    }
}
