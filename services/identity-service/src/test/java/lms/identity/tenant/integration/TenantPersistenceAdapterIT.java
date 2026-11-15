package lms.identity.tenant.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import lms.tenant.TenantAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cần Docker. Kiểm chứng ánh xạ JPA và adapter của context tenant trên schema thật (V1-V11). */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration/identity"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// Phiên Hibernate cần bộ phân giải tenant vì identity có entity @TenantId (bảng của test này là bảng toàn cục)
@ImportAutoConfiguration(TenantAutoConfiguration.class)
@Testcontainers
@ComponentScan("lms.identity.tenant.infrastructure.persistence")
class TenantPersistenceAdapterIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TenantRepository tenants;
    @Autowired
    MembershipRepository memberships;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void timTenantMacDinhTheoMa() {
        assertThat(tenants.findByCode("default")).get()
                .extracting(Tenant::name, Tenant::isActive).containsExactly("Thư viện mặc định", true);
    }

    @Test
    void luuTenantMoiVaTrungMaBiTuChoi() {
        Tenant t = tenants.save(Tenant.create(code("lib-a"), "Thư viện A", null));

        assertThat(tenants.findById(t.id())).isPresent();
        assertThatThrownBy(() -> tenants.save(Tenant.create(t.code(), "Khác", null)))
                .isInstanceOf(DuplicateTenantException.class);
    }

    @Test
    void membershipLuuTimVaChiLayActive() {
        Tenant t = tenants.save(Tenant.create(code("lib-b"), "Thư viện B", null));
        UUID user = UUID.randomUUID();
        Membership m = Membership.create(user, t.id(), null);
        memberships.save(m);

        assertThat(memberships.find(user, t.id())).isPresent();
        assertThat(memberships.findActiveByUser(user)).hasSize(1);

        m.disable();
        memberships.save(m);
        assertThat(memberships.findActiveByUser(user)).isEmpty();
        assertThat(memberships.find(user, t.id())).get().extracting(Membership::isActive).isEqualTo(false);
    }

    @Test
    void lastTenantDocGhiQuaCotCredentials() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        jdbc.update("INSERT INTO credentials (user_id, username, password_hash, active) VALUES (?, ?, 'h', true)",
                user, "u" + user);

        assertThat(memberships.findLastTenant(user)).isEmpty();
        memberships.saveLastTenant(user, tenant);
        assertThat(memberships.findLastTenant(user)).contains(tenant);

        UUID other = tenants.save(Tenant.create(code("lib-c"), "Thư viện C", null)).id();
        memberships.saveLastTenant(user, other);
        assertThat(memberships.findLastTenant(user)).contains(other);
    }

    @Test
    void lastTenantVoiTaiKhoanChiCoCredentialKhongCoDongUsers() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        jdbc.update("INSERT INTO credentials (user_id, username, password_hash, active) VALUES (?, ?, 'h', true)",
                user, "c" + user);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, user)).isZero();

        memberships.saveLastTenant(user, tenant);

        assertThat(memberships.findLastTenant(user)).contains(tenant);
    }

    /** Mã tenant duy nhất mỗi lần gọi, tránh đụng unique(code) khi chạy lại hoặc dùng chung container. */
    private static String code(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void lastTenantVoiTaiKhoanChiCoTrongUsersKhongCoCredentialLaNoOp() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, full_name, email, status) VALUES (?, ?, 'N', ?, 'ACTIVE')",
                user, "uo" + user, user + "@x.vn");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credentials WHERE user_id = ?", Integer.class, user))
                .isZero();

        memberships.saveLastTenant(user, UUID.fromString("00000000-0000-0000-0000-0000000000d1"));

        assertThat(memberships.findLastTenant(user)).isEmpty();
    }

    @Test
    void lastTenantVoiUserIdKhongTonTaiLaNoOp() {
        UUID unknown = UUID.randomUUID();

        memberships.saveLastTenant(unknown, UUID.fromString("00000000-0000-0000-0000-0000000000d1"));

        assertThat(memberships.findLastTenant(unknown)).isEmpty();
    }
}
