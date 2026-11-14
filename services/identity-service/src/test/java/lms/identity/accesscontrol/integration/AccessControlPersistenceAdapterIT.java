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
        // Cờ is_admin là dữ liệu: chỉ SUPER_ADMIN được seed là quản trị
        assertThat(roles.stream().filter(Role::isAdmin).map(Role::name)).containsExactly("SUPER_ADMIN");
    }

    @Test
    void migrationDoiMaPermissionCuSangKhoaEndpointVaGiuNguyenQuyenCuaRole() {
        var codes = adapter.findAllPermissions().stream().map(Permission::code).toList();
        Role member = adapter.findAll().stream().filter(r -> r.name().equals("MEMBER")).findFirst().orElseThrow();

        assertThat(codes).doesNotContain("BORROW_BOOK", "USER_VIEW", "ROLE_ASSIGN", "AUDIT_VIEW");
        assertThat(codes).contains("BorrowingController/borrow", "AccessControlController/assignRole");
        assertThat(member.permissionCodes()).contains("BorrowingController/borrow", "ReservationController/reserve");
        var borrow = adapter.findAllPermissions().stream()
                .filter(p -> p.code().equals("BorrowingController/borrow")).findFirst().orElseThrow();
        assertThat(borrow.source()).isEqualTo("AUTO");
        assertThat(borrow.service()).isEqualTo("circulation-service");
    }

    @Test
    void dangKyKhoaMoiIdempotentVaKhongGhiDeKhoaDaCo() {
        var discovered = new Permission("ProbeController/ping", "GET /api/probe/ping (x)", "AUTO", "x-service", "GET",
                "/api/probe/ping", java.time.Instant.parse("2026-01-01T00:00:00Z"));

        adapter.registerDiscovered(discovered);
        adapter.registerDiscovered(new Permission("ProbeController/ping", "mô tả khác", "AUTO", "y-service", "POST",
                "/khac", java.time.Instant.parse("2026-06-01T00:00:00Z")));

        var stored = adapter.findAllPermissions().stream()
                .filter(p -> p.code().equals("ProbeController/ping")).toList();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).service()).isEqualTo("x-service");
        assertThat(stored.get(0).description()).isEqualTo("GET /api/probe/ping (x)");
        assertThat(stored.get(0).firstSeenAt()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void taiKhoanQuanTriKhoiTaoCoRoleSuperAdmin() {
        UUID bootstrapAdmin = UUID.fromString("00000000-0000-0000-0000-000000000001");

        var assignment = adapter.findByUserId(bootstrapAdmin).orElseThrow();
        var permissions = new EffectivePermissionsService().resolve(adapter.findAllByIds(assignment.roleIds()));

        assertThat(permissions).contains("AccessControlController/assignRole", "BorrowingController/borrow",
                "ReaderProfileController/issue");
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
