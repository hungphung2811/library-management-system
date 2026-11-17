package lms.identity.accesscontrol.infrastructure.config;

import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PermissionManagementUseCase;
import lms.identity.accesscontrol.application.RegisterPermissionKeyUseCase;
import lms.identity.accesscontrol.infrastructure.security.LocalPermissionKeyRegistrar;
import lms.security.permission.PermissionKeyRegistrar;
import java.time.Duration;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.domain.EffectivePermissionsService;
import lms.identity.accesscontrol.infrastructure.messaging.KafkaAccessEventPublisher;
import lms.identity.accesscontrol.infrastructure.persistence.AccessControlPersistenceAdapter;
import lms.identity.accesscontrol.infrastructure.persistence.PermissionJpaRepository;
import lms.identity.accesscontrol.infrastructure.persistence.RoleJpaRepository;
import lms.identity.accesscontrol.infrastructure.persistence.UserRoleJpaRepository;
import lms.messaging.KafkaEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/** Composition root: nối domain/application (không có annotation Spring) với adapter. */
@Configuration
public class AccessControlBeanConfiguration {

    @Bean
    AccessControlPersistenceAdapter persistenceAdapter(RoleJpaRepository roles, PermissionJpaRepository permissions,
                                                       UserRoleJpaRepository userRoles) {
        return new AccessControlPersistenceAdapter(roles, permissions, userRoles);
    }

    @Bean
    KafkaAccessEventPublisher accessEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                                   @Value("${app.kafka.topics.access-events}") String topic) {
        return new KafkaAccessEventPublisher(new KafkaEventPublisher(kafkaTemplate, objectMapper, topic));
    }

    @Bean
    NewTopic accessEventsTopic(@Value("${app.kafka.topics.access-events}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    EffectivePermissionsService effectivePermissionsService() {
        return new EffectivePermissionsService();
    }

    @Bean
    PermissionManagementUseCase permissionManagementUseCase(AccessControlPersistenceAdapter persistence) {
        return new PermissionManagementUseCase(persistence, persistence);
    }

    @Bean
    RegisterPermissionKeyUseCase registerPermissionKeyUseCase(AccessControlPersistenceAdapter persistence) {
        return new RegisterPermissionKeyUseCase(persistence);
    }

    /** Thay cho registrar REST mặc định của security-common: identity ghi thẳng vào DB của mình. */
    @Bean
    PermissionKeyRegistrar localPermissionKeyRegistrar(RegisterPermissionKeyUseCase useCase, Clock clock) {
        return new LocalPermissionKeyRegistrar(useCase, clock, Duration.ofSeconds(30));
    }

    @Bean
    RoleManagementUseCase roleManagementUseCase(AccessControlPersistenceAdapter persistence) {
        return new RoleManagementUseCase(persistence, persistence, persistence, persistence);
    }

    @Bean
    UserRoleUseCase userRoleUseCase(AccessControlPersistenceAdapter persistence, KafkaAccessEventPublisher publisher,
                                    Clock clock) {
        return new UserRoleUseCase(persistence, persistence, persistence, publisher, clock);
    }

    /** Role tự đăng ký của tenant (cờ signup_default), cho luồng đăng ký công khai của context auth. */
    @Bean
    lms.identity.accesscontrol.application.SignupRoleUseCase signupRoleUseCase(
            AccessControlPersistenceAdapter persistence, UserRoleUseCase userRoleUseCase) {
        return new lms.identity.accesscontrol.application.SignupRoleUseCase(persistence, userRoleUseCase);
    }

    /** Role của tenant mới từ role_templates, gán/gỡ quản trị viên tenant cho thao tác nền tảng (context tenant). */
    @Bean
    lms.identity.tenant.application.TenantRoleProvisioningPort tenantRoleProvisioningPort(
            AccessControlPersistenceAdapter persistence, UserRoleUseCase userRoleUseCase,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new lms.identity.accesscontrol.infrastructure.platform.TenantRoleProvisioningAdapter(persistence,
                persistence, persistence, userRoleUseCase, jdbcTemplate);
    }

    @Bean
    GetUserAccessUseCase getUserAccessUseCase(AccessControlPersistenceAdapter persistence,
                                              EffectivePermissionsService permissionsService) {
        return new GetUserAccessUseCase(persistence, persistence, permissionsService);
    }
}
