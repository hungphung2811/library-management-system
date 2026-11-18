package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.infrastructure.persistence.AccessControlPersistenceAdapter;
import lms.identity.accesscontrol.infrastructure.persistence.PermissionJpaRepository;
import lms.identity.accesscontrol.infrastructure.persistence.RoleJpaRepository;
import lms.identity.accesscontrol.infrastructure.persistence.UserRoleJpaRepository;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.tenant.TenantAutoConfiguration;
import lms.tenant.TenantContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. ADR-0022 (phương án A): số câu SQL khi cấp token không được tăng theo số tenant (M) và số role (R) của
 * người dùng. Đo bằng Hibernate Statistics (số PreparedStatement), vai trò identity_app (chịu RLS).
 * Chạy: mvn -pl services/identity-service -am test -Dtest=TokenIssuingQueryCountIT
 */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration/identity",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(TenantAutoConfiguration.class)
@Testcontainers
@ComponentScan("lms.identity.tenant.infrastructure.persistence")
@Import(TokenIssuingQueryCountIT.Config.class)
class TokenIssuingQueryCountIT {

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

        @Bean
        ListMyTenantsUseCase listMyTenants(TenantRepository tenants, MembershipRepository memberships,
                                           TenantModuleRepository modules) {
            return new ListMyTenantsUseCase(tenants, memberships, modules);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired
    AccessControlPersistenceAdapter roleAdapter;
    @Autowired
    ListMyTenantsUseCase listMyTenants;
    @Autowired
    EntityManager entityManager;

    private static void asSuperuser(String sql) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private Statistics statistics() {
        return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    /** Tạo role (kèm 3 permission mỗi role, mã có sẵn từ seed) trong tenant mặc định, trả về id. */
    private List<RoleId> seedRoles(String prefix, int count) throws Exception {
        List<RoleId> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            asSuperuser("INSERT INTO roles (id, tenant_id, name, description) VALUES ('" + id + "', '" + DEFAULT_TENANT
                    + "', '" + prefix + i + "', 'đo số câu')");
            asSuperuser("INSERT INTO role_permissions (role_id, permission_code) VALUES ('" + id
                    + "', 'BorrowingController/borrow')");
            ids.add(new RoleId(id));
        }
        return ids;
    }

    private UUID seedUserWithTenants(String prefix, int count) throws Exception {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < count; i++) {
            UUID tenant = UUID.randomUUID();
            asSuperuser("INSERT INTO tenants (id, code, name, status) VALUES ('" + tenant + "', '" + prefix + i
                    + "', 'T " + prefix + i + "', 'ACTIVE')");
            asSuperuser("INSERT INTO tenant_memberships (user_id, tenant_id, status) VALUES ('" + user + "', '"
                    + tenant + "', 'ACTIVE')");
        }
        return user;
    }

    private long statements(Runnable action) {
        entityManager.flush();
        entityManager.clear();
        Statistics stats = statistics();
        stats.clear();
        action.run();
        return stats.getPrepareStatementCount();
    }

    @Test
    void soCauNapRoleVaPermissionKhongTangTheoSoRole() throws Exception {
        List<RoleId> one = seedRoles("qc-r1-", 1);
        List<RoleId> many = seedRoles("qc-r12-", 12);

        long forOne = statements(() -> assertThat(roleAdapter.findAllByIds(one)).hasSize(1));
        long forMany = statements(() -> {
            List<Role> roles = roleAdapter.findAllByIds(many);
            assertThat(roles).hasSize(12);
            assertThat(roles).allSatisfy(r -> assertThat(r.permissionCodes()).containsExactly("BorrowingController/borrow"));
        });

        // roles + một câu nạp role_permissions theo lô; trước tối ưu: 1 + R
        assertThat(forMany).isEqualTo(forOne).isLessThanOrEqualTo(2);
    }

    @Test
    void soCauDanhSachTenantTruyCapDuocKhongTangTheoSoTenant() throws Exception {
        UUID userOne = seedUserWithTenants("qc-t1-", 1);
        UUID userMany = seedUserWithTenants("qc-t8-", 8);

        long forOne = statements(() -> assertThat(listMyTenants.execute(userOne)).hasSize(1));
        long forMany = statements(() -> {
            List<TenantSummary> list = listMyTenants.execute(userMany);
            assertThat(list).hasSize(8);
        });

        // memberships + tenants (một lần) + modules; trước tối ưu: 2 + M
        assertThat(forMany).isEqualTo(forOne).isLessThanOrEqualTo(3);
    }
}
