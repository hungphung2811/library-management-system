package lms.identity.apiclient.infrastructure.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import lms.identity.apiclient.application.ApplicationPolicy;
import lms.identity.apiclient.application.ApplicationRevocationPort;
import lms.identity.apiclient.application.ApplicationUsagePort;
import lms.identity.apiclient.application.AuthenticateClientUseCase;
import lms.identity.apiclient.application.CreateApplicationUseCase;
import lms.identity.apiclient.application.ManageApplicationDomainsUseCase;
import lms.identity.apiclient.application.ManageApplicationUseCase;
import lms.identity.apiclient.application.ManageCredentialsUseCase;
import lms.identity.apiclient.application.QueryApplicationUsageUseCase;
import lms.identity.apiclient.application.QueryApplicationUseCase;
import lms.identity.apiclient.application.TenantContractGate;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.infrastructure.persistence.RedisApplicationRevocationAdapter;
import lms.identity.contract.application.GetMyContractsUseCase;
import lms.identity.tenant.application.ManageApplicationOriginsUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.TenantAvailabilityQuery;
import lms.identity.tenant.application.TransactionalRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    /** Xác thực client của token endpoint OAuth2 client_credentials (pha I2). */
    @Bean
    AuthenticateClientUseCase authenticateClientUseCase(ApplicationRepository applications,
                                                        ApplicationCredentialRepository credentials,
                                                        TenantAvailabilityQuery tenantAvailability,
                                                        TransactionalRunner tenantTransactionalRunner, Clock clock) {
        return new AuthenticateClientUseCase(applications, credentials, tenantAvailability,
                tenantTransactionalRunner, clock);
    }

    /**
     * Thu hồi token tức thời (tùy chọn): bật bằng {@code lms.identity.integration.revocation.enabled=true}
     * (APPLICATIONS_REVOCATION_ENABLED) thì ghi dấu vào Redis cho integration-api; mặc định tắt (không chạm Redis).
     */
    @Bean
    ApplicationRevocationPort applicationRevocationPort(
            @Value("${lms.identity.integration.revocation.enabled:false}") boolean enabled,
            @Value("${lms.identity.integration.revocation.marker-ttl:PT1H}") Duration markerTtl,
            ObjectProvider<StringRedisTemplate> redis) {
        StringRedisTemplate template = enabled ? redis.getIfAvailable() : null;
        return template == null ? ApplicationRevocationPort.NONE
                : new RedisApplicationRevocationAdapter(template, markerTtl);
    }

    @Bean
    QueryApplicationUsageUseCase queryApplicationUsageUseCase(ApplicationRepository applications,
                                                              ApplicationUsagePort usage, Clock clock) {
        return new QueryApplicationUsageUseCase(applications, usage, clock);
    }

    /** Phân giải site key công khai cho cấp token storefront (Public Site Key, pha P2). */
    @Bean
    lms.identity.apiclient.application.ResolveSiteKeyUseCase resolveSiteKeyUseCase(
            ApplicationRepository applications,
            lms.identity.tenant.application.ResolveStorefrontTenantUseCase storefrontTenants) {
        return new lms.identity.apiclient.application.ResolveSiteKeyUseCase(applications, storefrontTenants);
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
                                                      SecureRandom applicationSecureRandom, Clock clock,
                                                      ApplicationRevocationPort applicationRevocationPort) {
        return new ManageApplicationUseCase(applications, credentials, history, origins, events,
                tenantTransactionalRunner, applicationSecureRandom, clock, applicationRevocationPort);
    }

    @Bean
    ManageCredentialsUseCase manageCredentialsUseCase(ApplicationRepository applications,
                                                      ApplicationCredentialRepository credentials,
                                                      ApplicationHistoryRepository history,
                                                      PublishPlatformEventPort events,
                                                      TransactionalRunner tenantTransactionalRunner,
                                                      ApplicationPolicy policy, SecureRandom applicationSecureRandom,
                                                      Clock clock, ApplicationRevocationPort applicationRevocationPort) {
        return new ManageCredentialsUseCase(applications, credentials, history, events, tenantTransactionalRunner,
                policy, applicationSecureRandom, clock, applicationRevocationPort);
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
