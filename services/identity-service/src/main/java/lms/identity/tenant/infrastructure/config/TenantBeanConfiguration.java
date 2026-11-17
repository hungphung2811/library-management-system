package lms.identity.tenant.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.AddStorefrontOriginUseCase;
import lms.identity.tenant.application.ListMyStorefrontOriginsUseCase;
import lms.identity.tenant.application.ListStorefrontOriginsUseCase;
import lms.identity.tenant.application.RemoveStorefrontOriginUseCase;
import lms.identity.tenant.application.SetStorefrontOriginEnabledUseCase;
import lms.identity.tenant.application.StorefrontOriginPolicy;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.application.MembershipLookupUseCase;
import lms.identity.tenant.application.AddTenantAdminUseCase;
import lms.identity.tenant.application.AttachUserToTenantUseCase;
import lms.identity.tenant.application.ChangeMembershipDepartmentUseCase;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.DisableTenantModuleUseCase;
import lms.identity.tenant.application.EnableTenantModuleUseCase;
import lms.identity.tenant.application.GetTenantUseCase;
import lms.identity.tenant.application.ListActiveTenantIdsUseCase;
import lms.identity.tenant.application.ListActiveTenantAdminsQuery;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.ManualModuleChangeRecorder;
import lms.identity.tenant.application.TenantModuleOperations;
import lms.identity.tenant.application.ListTenantModulesUseCase;
import lms.identity.tenant.application.ManageOrganizationUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.RemoveTenantAdminUseCase;
import lms.identity.tenant.application.ResolveLoginTenantUseCase;
import lms.identity.tenant.application.ResolveStorefrontTenantUseCase;
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
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantQueryRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.infrastructure.messaging.KafkaPlatformEventPublisher;
import lms.messaging.KafkaEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.ObjectProvider;
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
    ResolveLoginTenantUseCase resolveLoginTenantUseCase(TenantRepository tenants, MembershipRepository memberships,
                                                        TenantModuleRepository modules) {
        return new ResolveLoginTenantUseCase(tenants, memberships, modules);
    }

    @Bean
    ResolveStorefrontTenantUseCase resolveStorefrontTenantUseCase(TenantRepository tenants,
                                                                  TenantModuleRepository modules,
                                                                  StorefrontOriginRepository origins) {
        return new ResolveStorefrontTenantUseCase(tenants, modules, origins);
    }

    // ---- origin storefront theo tenant (ADR-0013) ----

    @Bean
    StorefrontOriginPolicy storefrontOriginPolicy(
            @Value("${lms.identity.storefront.max-origins-per-tenant:10}") int max,
            @Value("${lms.identity.storefront.allow-http-origins:false}") boolean allowHttp) {
        return new StorefrontOriginPolicy(max, allowHttp);
    }

    @Bean
    ListStorefrontOriginsUseCase listStorefrontOriginsUseCase(TenantRepository tenants,
                                                              StorefrontOriginRepository origins,
                                                              StorefrontOriginPolicy policy) {
        return new ListStorefrontOriginsUseCase(tenants, origins, policy);
    }

    @Bean
    AddStorefrontOriginUseCase addStorefrontOriginUseCase(TenantRepository tenants, StorefrontOriginRepository origins,
                                                          PublishPlatformEventPort events,
                                                          TransactionalRunner tenantTransactionalRunner,
                                                          StorefrontOriginPolicy policy) {
        return new AddStorefrontOriginUseCase(tenants, origins, events, tenantTransactionalRunner, policy);
    }

    @Bean
    SetStorefrontOriginEnabledUseCase setStorefrontOriginEnabledUseCase(TenantRepository tenants,
                                                                        StorefrontOriginRepository origins,
                                                                        PublishPlatformEventPort events,
                                                                        TransactionalRunner tenantTransactionalRunner,
                                                                        StorefrontOriginPolicy policy) {
        return new SetStorefrontOriginEnabledUseCase(tenants, origins, events, tenantTransactionalRunner, policy);
    }

    @Bean
    RemoveStorefrontOriginUseCase removeStorefrontOriginUseCase(TenantRepository tenants,
                                                                StorefrontOriginRepository origins,
                                                                PublishPlatformEventPort events,
                                                                TransactionalRunner tenantTransactionalRunner,
                                                                StorefrontOriginPolicy policy) {
        return new RemoveStorefrontOriginUseCase(tenants, origins, events, tenantTransactionalRunner, policy);
    }

    @Bean
    ListMyStorefrontOriginsUseCase listMyStorefrontOriginsUseCase(StorefrontOriginRepository origins) {
        return new ListMyStorefrontOriginsUseCase(origins);
    }

    @Bean
    SwitchTenantUseCase switchTenantUseCase(TenantRepository tenants, MembershipRepository memberships,
                                            TenantModuleRepository modules) {
        return new SwitchTenantUseCase(tenants, memberships, modules);
    }

    @Bean
    VerifyTenantAccessUseCase verifyTenantAccessUseCase(TenantRepository tenants, MembershipRepository memberships,
                                                        TenantModuleRepository modules) {
        return new VerifyTenantAccessUseCase(tenants, memberships, modules);
    }

    @Bean
    ListMyTenantsUseCase listMyTenantsUseCase(TenantRepository tenants, MembershipRepository memberships,
                                              TenantModuleRepository modules) {
        return new ListMyTenantsUseCase(tenants, memberships, modules);
    }

    @Bean
    ListActiveTenantIdsUseCase listActiveTenantIdsUseCase(TenantRepository tenants) {
        return new ListActiveTenantIdsUseCase(tenants);
    }

    @Bean
    lms.identity.tenant.application.TenantOverviewQuery tenantOverviewQuery(TenantRepository tenants,
                                                                            TenantModuleRepository modules) {
        return new lms.identity.tenant.application.TenantOverviewQuery(tenants, modules);
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
                                            TransactionalRunner tenantTransactionalRunner,
                                            TenantModuleRepository modules) {
        return new CreateTenantUseCase(tenants, organizations, memberships, roles, accounts, events,
                tenantTransactionalRunner, modules);
    }

    @Bean
    UpdateTenantUseCase updateTenantUseCase(TenantRepository tenants, OrganizationRepository organizations,
                                            TenantQueryRepository queries, PublishPlatformEventPort events,
                                            TransactionalRunner tenantTransactionalRunner,
                                            TenantModuleRepository modules) {
        return new UpdateTenantUseCase(tenants, organizations, queries, events, tenantTransactionalRunner, modules);
    }

    @Bean
    SetTenantStatusUseCase setTenantStatusUseCase(TenantRepository tenants, TenantQueryRepository queries,
                                                  PublishPlatformEventPort events,
                                                  TransactionalRunner tenantTransactionalRunner,
                                                  TenantModuleRepository modules) {
        return new SetTenantStatusUseCase(tenants, queries, events, tenantTransactionalRunner, modules);
    }

    @Bean
    SearchTenantsUseCase searchTenantsUseCase(TenantQueryRepository queries, TenantModuleRepository modules) {
        return new SearchTenantsUseCase(queries, modules);
    }

    @Bean
    GetTenantUseCase getTenantUseCase(TenantRepository tenants, TenantQueryRepository queries,
                                      TenantRoleProvisioningPort roles, TransactionalRunner tenantTransactionalRunner,
                                      TenantModuleRepository modules) {
        return new GetTenantUseCase(tenants, queries, roles, tenantTransactionalRunner, modules);
    }

    // ---- module theo tenant (ADR-0009) ----

    @Bean
    ListTenantModulesUseCase listTenantModulesUseCase(TenantRepository tenants, TenantModuleRepository modules) {
        return new ListTenantModulesUseCase(tenants, modules);
    }

    @Bean
    EnableTenantModuleUseCase enableTenantModuleUseCase(TenantRepository tenants, TenantModuleRepository modules,
                                                        TenantRoleProvisioningPort roles,
                                                        PublishPlatformEventPort events,
                                                        TransactionalRunner tenantTransactionalRunner,
                                                        ObjectProvider<ManualModuleChangeRecorder> recorder) {
        return new EnableTenantModuleUseCase(tenants, modules, roles, events, tenantTransactionalRunner,
                recorder.getIfAvailable());
    }

    @Bean
    DisableTenantModuleUseCase disableTenantModuleUseCase(TenantRepository tenants, TenantModuleRepository modules,
                                                          PublishPlatformEventPort events,
                                                          TransactionalRunner tenantTransactionalRunner,
                                                          ObjectProvider<ManualModuleChangeRecorder> recorder) {
        return new DisableTenantModuleUseCase(tenants, modules, events, tenantTransactionalRunner,
                recorder.getIfAvailable());
    }

    /** Thao tác lõi bật/tắt module và đổi trạng thái tenant do hợp đồng (ADR-0012); context contract dùng. */
    @Bean
    TenantModuleOperations tenantModuleOperations(TenantRepository tenants, TenantModuleRepository modules,
                                                  TenantRoleProvisioningPort roles) {
        return new TenantModuleOperations(tenants, modules, roles);
    }

    @Bean
    ListActiveTenantAdminsQuery listActiveTenantAdminsQuery(TenantRoleProvisioningPort roles,
                                                            MembershipRepository memberships) {
        return new ListActiveTenantAdminsQuery(roles, memberships);
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
