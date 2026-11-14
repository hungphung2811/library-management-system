package lms.identity.accesscontrol.integration;

import static org.assertj.core.api.Assertions.assertThat;

import lms.identity.accesscontrol.domain.UserRoleAssignment;
import lms.identity.accesscontrol.domain.Permission;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.EffectivePermissionsService;
import lms.identity.accesscontrol.infrastructure.persistence.AccessControlPersistenceAdapter;
import lms.identity.accesscontrol.infrastructure.persistence.PermissionJpaRepository;
import lms.identity.accesscontrol.infrastructure.persistence.RoleJpaRepository;
import lms.identity.accesscontrol.infrastructure.persistence.UserRoleJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cần Docker. Chạy: mvn test -Dtest=AccessControlPersistenceAdapterIT. Kiểm chứng cả migration seed V2. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AccessControlPersistenceAdapterIT.Config.class)
class AccessControlPersistenceAdapterIT {

    @TestConfiguration
    static class Config {
        @Bean
        AccessControlPersistenceAdapter adapter(RoleJpaRepository roles, PermissionJpaRepository permissions,
                                                UserRoleJpaRepository userRoles) {
            return new AccessControlPersistenceAdapter(roles, permissions, userRoles);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AccessControlPersistenceAdapter adapter;

    @Test
    void migrationSeedCoSuperAdminVoiToanBoPermission() {
        List<Role> roles = adapter.findAll();
        Role superAdmin = roles.stream().filter(r -> r.name().equals("SUPER_ADMIN")).findFirst().orElseThrow();

        assertThat(roles).extracting(Role::name)
                .contains("SUPER_ADMIN", "LIBRARY_ADMIN", "LIBRARIAN", "DEPARTMENT_MANAGER", "MEMBER");
        assertThat(superAdmin.permissionCodes()).hasSize(adapter.findAllPermissions().size());
    }

    @Test
    void taiKhoanQuanTriKhoiTaoCoRoleSuperAdmin() {
        UUID bootstrapAdmin = UUID.fromString("00000000-0000-0000-0000-000000000001");

        var assignment = adapter.findByUserId(bootstrapAdmin).orElseThrow();
        var permissions = new EffectivePermissionsService().resolve(adapter.findAllByIds(assignment.roleIds()));

        assertThat(permissions).contains("ROLE_ASSIGN", "BORROW_BOOK", "USER_DELETE");
    }

    @Test
    void taoRoleCapPermissionGanChoNguoiDungRoiThuHoi() {
        adapter.save(new Permission("REPORT_VIEW", "Xem báo cáo"));
        Role role = Role.create("AUDITOR", "Kiểm toán");
        role.grant("REPORT_VIEW");
        adapter.save(role);

        UUID user = UUID.randomUUID();
        UserRoleAssignment assignment = UserRoleAssignment.empty(user);
        assignment.assign(role.id(), role.name(), Instant.now());
        adapter.save(assignment);

        assertThat(adapter.existsByName("AUDITOR")).isTrue();
        assertThat(adapter.findById(role.id()).orElseThrow().permissionCodes()).containsExactly("REPORT_VIEW");
        assertThat(adapter.findByUserId(user).orElseThrow().roleIds()).containsExactly(role.id());

        UserRoleAssignment loaded = adapter.findByUserId(user).orElseThrow();
        loaded.revoke(role.id(), role.name(), Instant.now());
        adapter.save(loaded);

        assertThat(adapter.findByUserId(user)).isEmpty();
    }
}
