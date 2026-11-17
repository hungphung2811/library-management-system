package lms.identity.demo.infrastructure.config;

import lms.identity.accesscontrol.application.AssignRoleByNameUseCase;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ManageContractUseCase;
import lms.identity.demo.application.SeedDemoIdentityUseCase;
import lms.identity.demo.infrastructure.IdentityDemoSeedStep;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.FindTenantByCodeQuery;
import lms.identity.user.application.EnsureReaderCardUseCase;
import lms.identity.user.application.EnsureUserUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Composition root của seed demo (ADR-0018); chỉ tồn tại khi profile {@code demo-seed} bật. */
@Configuration
@Profile("demo-seed")
class DemoSeedBeanConfiguration {

    @Bean
    SeedDemoIdentityUseCase seedDemoIdentityUseCase(FindTenantByCodeQuery tenants, CreateTenantUseCase createTenant,
                                                    ContractQueryUseCase contractQuery,
                                                    ManageContractUseCase manageContract,
                                                    EnsureUserUseCase ensureUser,
                                                    EnsureReaderCardUseCase ensureReaderCard,
                                                    AssignRoleByNameUseCase assignRole) {
        return new SeedDemoIdentityUseCase(tenants, createTenant, contractQuery, manageContract, ensureUser,
                ensureReaderCard, assignRole);
    }

    @Bean
    IdentityDemoSeedStep identityDemoSeedStep(SeedDemoIdentityUseCase useCase) {
        return new IdentityDemoSeedStep(useCase);
    }
}
