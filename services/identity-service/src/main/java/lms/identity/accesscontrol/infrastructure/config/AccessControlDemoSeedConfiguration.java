package lms.identity.accesscontrol.infrastructure.config;

import lms.identity.accesscontrol.application.AssignRoleByNameUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Bean phục vụ seed dữ liệu demo (ADR-0018); chỉ tồn tại khi profile {@code demo-seed} bật. */
@Configuration
@Profile("demo-seed")
class AccessControlDemoSeedConfiguration {

    @Bean
    AssignRoleByNameUseCase assignRoleByNameUseCase(RoleRepository roles, UserRoleRepository assignments,
                                                    UserRoleUseCase userRoles) {
        return new AssignRoleByNameUseCase(roles, assignments, userRoles);
    }
}
