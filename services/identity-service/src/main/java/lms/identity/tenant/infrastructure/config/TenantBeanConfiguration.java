package lms.identity.tenant.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.MembershipLookupUseCase;
import lms.identity.tenant.application.AddTenantAdminUseCase;
import lms.identity.tenant.application.AttachUserToTenantUseCase;
import lms.identity.tenant.application.ChangeMembershipDepartmentUseCase;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.GetTenantUseCase;
import lms.identity.tenant.application.ListActiveTenantIdsUseCase;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.ManageOrganizationUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.RemoveTenantAdminUseCase;
import lms.identity.tenant.application.ResolveLoginTenantUseCase;
import lms.identity.tenant.application.SearchTenantsUseCase;
import lms.identity.tenant.application.SetTenantStatusUseCase;
import lms.identity.tenant.application.SwitchTenantUseCase;
import lms.identity.tenant.application.TenantAccountPort;
import lms.identity.tenant.application.TenantRoleProvisioningPort;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.tenant.application.UpdateTenantUseCase;
import lms.identity.tenant.application.VerifyTenantAccessUseCase;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.OrganizationRepository;
import lms.identity.tenant.domain.TenantQueryRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.infrastructure.messaging.KafkaPlatformEventPublisher;
import lms.messaging.KafkaEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Composition root của context tenant (adapter persistence tự đăng ký bằng @Repository). TenantRoleProvisioningPort do
 * accesscontrol cung cấp, TenantAccountPort do user cung cấp (tenant không biết các context đó).
 */
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
    ListActiveTenantIdsUseCase listActiveTenantIdsUseCase(TenantRepository tenants) {
        return new ListActiveTenantIdsUseCase(tenants);
    }

    @Bean
    AddMembershipUseCase addMembershipUseCase(TenantRepository tenants, MembershipRepository memberships) {
        return new AddMembershipUseCase(tenants, memberships);
    }

    @Bean
    MembershipLookupUseCase membershipLookupUseCase(MembershipRepository memberships) {
        return new MembershipLookupUseCase(memberships);
    }

    @Bean
    ChangeMembershipDepartmentUseCase changeMembershipDepartmentUseCase(MembershipRepository memberships) {
        return new ChangeMembershipDepartmentUseCase(memberships);
    }

    // ---- quản lý nền tảng (Task 6) ----

    @Bean
    PublishPlatformEventPort platformEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                                    ObjectMapper objectMapper,
                                                    @Value("${app.kafka.topics.identity-events}") String topic) {
        return new KafkaPlatformEventPublisher(new KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    NewTopic identityEventsTopic(@Value("${app.kafka.topics.identity-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    TransactionalRunner tenantTransactionalRunner() {
        return new TransactionalRunner();
    }

    @Bean
    CreateTenantUseCase createTenantUseCase(TenantRepository tenants, OrganizationRepository organizations,
                                            MembershipRepository memberships, TenantRoleProvisioningPort roles,
                                            TenantAccountPort accounts, PublishPlatformEventPort events,
                                            TransactionalRunner tenantTransactionalRunner) {
        return new CreateTenantUseCase(tenants, organizations, memberships, roles, accounts, events,
                tenantTransactionalRunner);
    }

    @Bean
    UpdateTenantUseCase updateTenantUseCase(TenantRepository tenants, OrganizationRepository organizations,
                                            TenantQueryRepository queries, PublishPlatformEventPort events,
                                            TransactionalRunner tenantTransactionalRunner) {
        return new UpdateTenantUseCase(tenants, organizations, queries, events, tenantTransactionalRunner);
    }

    @Bean
    SetTenantStatusUseCase setTenantStatusUseCase(TenantRepository tenants, TenantQueryRepository queries,
                                                  PublishPlatformEventPort events,
                                                  TransactionalRunner tenantTransactionalRunner) {
        return new SetTenantStatusUseCase(tenants, queries, events, tenantTransactionalRunner);
    }

    @Bean
    SearchTenantsUseCase searchTenantsUseCase(TenantQueryRepository queries) {
        return new SearchTenantsUseCase(queries);
    }

    @Bean
    GetTenantUseCase getTenantUseCase(TenantRepository tenants, TenantQueryRepository queries,
                                      TenantRoleProvisioningPort roles, TransactionalRunner tenantTransactionalRunner) {
        return new GetTenantUseCase(tenants, queries, roles, tenantTransactionalRunner);
    }

    @Bean
    ManageOrganizationUseCase manageOrganizationUseCase(OrganizationRepository organizations) {
        return new ManageOrganizationUseCase(organizations);
    }

    @Bean
    AddTenantAdminUseCase addTenantAdminUseCase(TenantRepository tenants, MembershipRepository memberships,
                                                TenantRoleProvisioningPort roles, TenantAccountPort accounts,
                                                PublishPlatformEventPort events,
                                                TransactionalRunner tenantTransactionalRunner) {
        return new AddTenantAdminUseCase(tenants, memberships, roles, accounts, events, tenantTransactionalRunner);
    }

    @Bean
    RemoveTenantAdminUseCase removeTenantAdminUseCase(TenantRepository tenants, TenantRoleProvisioningPort roles,
                                                      PublishPlatformEventPort events,
                                                      TransactionalRunner tenantTransactionalRunner) {
        return new RemoveTenantAdminUseCase(tenants, roles, events, tenantTransactionalRunner);
    }

    @Bean
    AttachUserToTenantUseCase attachUserToTenantUseCase(TenantRepository tenants, MembershipRepository memberships,
                                                        TenantAccountPort accounts, PublishPlatformEventPort events,
                                                        TransactionalRunner tenantTransactionalRunner) {
        return new AttachUserToTenantUseCase(tenants, memberships, accounts, events, tenantTransactionalRunner);
    }
}
