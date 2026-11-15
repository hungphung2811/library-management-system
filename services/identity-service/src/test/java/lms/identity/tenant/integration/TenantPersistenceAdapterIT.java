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
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
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
        Tenant t = tenants.save(Tenant.create("lib-a", "Thư viện A", null));

        assertThat(tenants.findById(t.id())).isPresent();
        assertThatThrownBy(() -> tenants.save(Tenant.create("lib-a", "Khác", null)))
                .isInstanceOf(DuplicateTenantException.class);
    }

    @Test
    void membershipLuuTimVaChiLayActive() {
        Tenant t = tenants.save(Tenant.create("lib-b", "Thư viện B", null));
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
    void lastTenantDocGhiQuaCotUsers() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        jdbc.update("INSERT INTO users (id, username, full_name, email, status) VALUES (?, ?, 'N', ?, 'ACTIVE')",
                user, "u" + user, user + "@x.vn");

        assertThat(memberships.findLastTenant(user)).isEmpty();
        memberships.saveLastTenant(user, tenant);
        assertThat(memberships.findLastTenant(user)).contains(tenant);
    }
}
