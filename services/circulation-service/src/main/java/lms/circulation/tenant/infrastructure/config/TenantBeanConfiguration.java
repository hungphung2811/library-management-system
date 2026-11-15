package lms.circulation.tenant.infrastructure.config;

import lms.circulation.borrowing.application.ProvisionDefaultBorrowRulesUseCase;
import lms.circulation.tenant.application.ChangeTenantStatusUseCase;
import lms.circulation.tenant.application.RegisterTenantUseCase;
import lms.circulation.tenant.infrastructure.persistence.KnownTenantsJdbcAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composition root của danh bạ tenant trong circulation (known_tenants + event vòng đời tenant). */
@Configuration
public class TenantBeanConfiguration {

    /** Bean duy nhất cài ActiveTenantsPort (ExpireReservationsJob lấy qua ObjectProvider). */
    @Bean
    KnownTenantsJdbcAdapter knownTenantsJdbcAdapter(JdbcTemplate jdbc) {
        return new KnownTenantsJdbcAdapter(jdbc);
    }

    @Bean
    RegisterTenantUseCase registerTenantUseCase(KnownTenantsJdbcAdapter knownTenants,
                                                ProvisionDefaultBorrowRulesUseCase provisionBorrowRules) {
        return new RegisterTenantUseCase(knownTenants, provisionBorrowRules);
    }

    @Bean
    ChangeTenantStatusUseCase changeTenantStatusUseCase(KnownTenantsJdbcAdapter knownTenants) {
        return new ChangeTenantStatusUseCase(knownTenants);
    }
}
