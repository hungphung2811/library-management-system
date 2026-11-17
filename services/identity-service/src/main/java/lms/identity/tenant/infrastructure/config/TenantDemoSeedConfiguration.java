package lms.identity.tenant.infrastructure.config;

import lms.identity.tenant.application.FindTenantByCodeQuery;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Bean phục vụ seed dữ liệu demo (ADR-0018); chỉ tồn tại khi profile {@code demo-seed} bật. */
@Configuration
@Profile("demo-seed")
class TenantDemoSeedConfiguration {

    @Bean
    FindTenantByCodeQuery findTenantByCodeQuery(TenantRepository tenants) {
        return new FindTenantByCodeQuery(tenants);
    }
}
