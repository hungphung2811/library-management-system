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
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.integration.AppRoleDatabase;
import lms.tenant.TenantAutoConfiguration;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy: mvn test -Dtest=AccessControlPersistenceAdapterIT. Kiểm chứng cả migration seed V2.
 * Role và gán role thuộc tenant (Hibernate @TenantId + RLS): ứng dụng kết nối bằng vai trò identity_app (chịu RLS,
 * {@link AppRoleDatabase}) và mọi test chạy trong tenant mặc định. Tenant đặt ở @BeforeAll vì transaction của test
 * (tự rollback) mở trước @BeforeEach, mà tenant của phiên Hibernate và biến RLS được chốt khi transaction bắt đầu.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration/identity"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(TenantAutoConfiguration.class)
@Testcontainers
@Import(AccessControlPersistenceAdapterIT.Config.class)
class AccessControlPersistenceAdapterIT {

    /** Tenant mặc định do migration V11 tạo; dữ liệu seed V3 được V13 gán vào tenant này. */
    static final UUID DEFAULT_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @BeforeAll
    static void vaoTenantMacDinh() {
        TenantContext.set(DEFAULT_TENANT);
    }

    @AfterAll
    static void roiTenant() {
        TenantContext.clear();
    }

    @TestConfiguration
    static class Config {
        @Bean
        AccessControlPersistenceAdapter adapter(RoleJpaRepository roles, PermissionJpaRepository permissions,
                                                UserRoleJpaRepository userRoles) {
            return new AccessControlPersistenceAdapter(roles, permissions, userRoles);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired
    AccessControlPersistenceAdapter adapter;

    @Autowired
    EntityManager entityManager;

    /** Seed bằng superuser (bỏ qua RLS), commit ngay nên transaction của test thấy được (READ COMMITTED). */
    private static void asSuperuser(String sql) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static UUID tenantKhac(String code) throws Exception {
        UUID id = UUID.randomUUID();
        asSuperuser("INSERT INTO tenants (id, code, name, status) VALUES ('" + id + "', '" + code + "', 'T " + code
                + "', 'ACTIVE')");
        return id;
    }

    @Test
    void roleVaGanRoleCuaTenantKhacKhongThayDuocKeCaQuaSqlNative() throws Exception {
        UUID other = tenantKhac("acl-other");
        UUID otherRole = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        asSuperuser("INSERT INTO roles (id, tenant_id, name, description, is_admin) VALUES ('" + otherRole + "', '"
                + other + "', 'OTHER_ONLY', 'Chỉ ở tenant khác', TRUE)");
        asSuperuser("INSERT INTO role_permissions (role_id, permission_code) VALUES ('" + otherRole
                + "', 'BorrowingController/borrow')");
        asSuperuser("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('" + user + "', '" + otherRole
                + "', '" + other + "')");

        assertThat(adapter.findAll()).extracting(Role::name).doesNotContain("OTHER_ONLY");
        assertThat(adapter.existsByName("OTHER_ONLY")).isFalse();
        assertThat(adapter.findById(new lms.identity.accesscontrol.domain.RoleId(otherRole))).isEmpty();
        assertThat(adapter.findByUserId(user)).isEmpty();
        Number nativeCount = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM roles WHERE id = '" + otherRole + "'").getSingleResult();
        Number nativePermissions = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM role_permissions WHERE role_id = '" + otherRole + "'").getSingleResult();
        assertThat(nativeCount.intValue()).isZero();
        assertThat(nativePermissions.intValue()).isZero();
    }

    @Test
    void tenRoleChiUniqueTrongTungTenantVaRoleMoiGanTenantHienTai() throws Exception {
        UUID other = tenantKhac("acl-dup");
        // tên MEMBER đã có ở tenant mặc định (seed) nhưng tạo được ở tenant khác
        asSuperuser("INSERT INTO roles (id, tenant_id, name, description) VALUES (gen_random_uuid(), '" + other
                + "', 'MEMBER', 'MEMBER của tenant khác')");
        assertThat(adapter.findAll().stream().filter(r -> r.name().equals("MEMBER"))).hasSize(1);

        Role role = Role.create("TENANT_SCOPED", "Role mới");
        adapter.save(role);
        entityManager.flush();
        Object tenantOfRole = entityManager.createNativeQuery(
                "SELECT tenant_id FROM roles WHERE id = '" + role.id().value() + "'").getSingleResult();
        assertThat(tenantOfRole.toString()).isEqualTo(DEFAULT_TENANT.toString());

        UUID user = UUID.randomUUID();
        UserRoleAssignment assignment = UserRoleAssignment.empty(user);
        assignment.assign(role.id(), role.name(), Instant.now());
        adapter.save(assignment);
        entityManager.flush();
        Object tenantOfAssignment = entityManager.createNativeQuery(
                "SELECT tenant_id FROM user_roles WHERE user_id = '" + user + "'").getSingleResult();
        assertThat(tenantOfAssignment.toString()).isEqualTo(DEFAULT_TENANT.toString());
    }
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
