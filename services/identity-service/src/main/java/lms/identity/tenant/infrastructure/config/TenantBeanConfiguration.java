package lms.identity.tenant.infrastructure.config;

import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.ResolveLoginTenantUseCase;
import lms.identity.tenant.application.SwitchTenantUseCase;
import lms.identity.tenant.application.VerifyTenantAccessUseCase;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context tenant (adapter persistence tự đăng ký bằng @Repository). */
@Configuration
public class TenantBeanConfiguration {

    @Bean
    ResolveLoginTenantUseCase resolveLoginTenantUseCase(TenantRepository tenants, MembershipRepository memberships) {
        return new ResolveLoginTenantUseCase(tenants, memberships);
    }

    @Bean
    SwitchTenantUseCase switchTenantUseCase(TenantRepository tenants, MembershipRepository memberships) {
        return new SwitchTenantUseCase(tenants, memberships);
    }

    @Bean
    VerifyTenantAccessUseCase verifyTenantAccessUseCase(TenantRepository tenants, MembershipRepository memberships) {
        return new VerifyTenantAccessUseCase(tenants, memberships);
    }

    @Bean
    ListMyTenantsUseCase listMyTenantsUseCase(TenantRepository tenants, MembershipRepository memberships) {
        return new ListMyTenantsUseCase(tenants, memberships);
    }

    @Bean
    AddMembershipUseCase addMembershipUseCase(TenantRepository tenants, MembershipRepository memberships) {
        return new AddMembershipUseCase(tenants, memberships);
    }
}
