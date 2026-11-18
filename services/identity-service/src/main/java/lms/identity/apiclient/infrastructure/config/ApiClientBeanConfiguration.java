package lms.identity.apiclient.infrastructure.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import lms.identity.apiclient.application.ApplicationPolicy;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.apiclient.application.TenantContractGate;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.contract.application.GetMyContractsUseCase;
import lms.identity.tenant.application.ManageApplicationOriginsUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TransactionalRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root của context apiclient (SaaS API Authentication, pha P1). Cấu hình (đều có mặc định, ghi đè bằng biến
 * môi trường/thuộc tính): {@code lms.identity.applications.max-per-tenant} (20), {@code credential-ttl} (P365D, Q13),
 * {@code credential-max-ttl} (P730D). Adapter persistence tự đăng ký bằng @Repository.
 */
@Configuration
public class ApiClientBeanConfiguration {

    @Bean
    ApplicationPolicy applicationPolicy(
            @Value("${lms.identity.applications.max-per-tenant:20}") int maxPerTenant,
            @Value("${lms.identity.applications.credential-ttl:P365D}") Duration credentialTtl,
            @Value("${lms.identity.applications.credential-max-ttl:P730D}") Duration credentialMaxTtl) {
        return new ApplicationPolicy(maxPerTenant, credentialTtl, credentialMaxTtl);
    }

    /** Nguồn ngẫu nhiên mật mã cho secret và site key. */
    @Bean
    SecureRandom applicationSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    TenantContractGate tenantContractGate(GetMyContractsUseCase contracts) {
        return new ContractBackedTenantContractGate(contracts);
    }

    @Bean
    CreateApplicationUseCase createApplicationUseCase(ApplicationRepository applications,
                                                      ApplicationHistoryRepository history,
                                                      PublishPlatformEventPort events, TenantContractGate contracts,
                                                      TransactionalRunner tenantTransactionalRunner,
                                                      ApplicationPolicy policy, SecureRandom applicationSecureRandom,
                                                      Clock clock) {
        return new CreateApplicationUseCase(applications, history, events, contracts, tenantTransactionalRunner,
                policy, applicationSecureRandom, clock);
    }

    @Bean
    QueryApplicationUseCase queryApplicationUseCase(ApplicationRepository applications,
                                                    ApplicationCredentialRepository credentials,
                                                    ApplicationHistoryRepository history, Clock clock) {
        return new QueryApplicationUseCase(applications, credentials, history, clock);
    }

    @Bean
    ManageApplicationUseCase manageApplicationUseCase(ApplicationRepository applications,
                                                      ApplicationCredentialRepository credentials,
                                                      ApplicationHistoryRepository history,
                                                      ManageApplicationOriginsUseCase origins,
                                                      PublishPlatformEventPort events,
                                                      TransactionalRunner tenantTransactionalRunner,
                                                      SecureRandom applicationSecureRandom, Clock clock) {
        return new ManageApplicationUseCase(applications, credentials, history, origins, events,
                tenantTransactionalRunner, applicationSecureRandom, clock);
    }

    @Bean
    ManageCredentialsUseCase manageCredentialsUseCase(ApplicationRepository applications,
                                                      ApplicationCredentialRepository credentials,
                                                      ApplicationHistoryRepository history,
                                                      PublishPlatformEventPort events,
                                                      TransactionalRunner tenantTransactionalRunner,
                                                      ApplicationPolicy policy, SecureRandom applicationSecureRandom,
                                                      Clock clock) {
        return new ManageCredentialsUseCase(applications, credentials, history, events, tenantTransactionalRunner,
                policy, applicationSecureRandom, clock);
    }

    @Bean
    ManageApplicationDomainsUseCase manageApplicationDomainsUseCase(ApplicationRepository applications,
                                                                    ManageApplicationOriginsUseCase origins,
                                                                    ApplicationHistoryRepository history,
                                                                    PublishPlatformEventPort events,
                                                                    TransactionalRunner tenantTransactionalRunner,
                                                                    Clock clock) {
        return new ManageApplicationDomainsUseCase(applications, origins, history, events,
                tenantTransactionalRunner, clock);
    }
}
