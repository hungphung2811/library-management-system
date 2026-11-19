package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedProperties;
import lms.demo.runner.DemoSeedRunner;
import lms.demo.runner.DemoSeedStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Seed demo của identity (ADR-0018) trên PostgreSQL thật, ứng dụng chạy bằng vai trò identity_app (chịu RLS) và profile
 * {@code demo-seed} (nạp application-demo-seed.yml), KHÔNG có web ({@code web-application-type=none} đến từ chính file
 * profile). Runner tự động bị loại khỏi IT (nó gọi System.exit); IT dựng runner với bộ thoát giả và gọi
 * {@code execute()} để kiểm guard, kết quả và idempotent. Cần Docker. Chạy: mvn -pl services/identity-service -am test
 * -Dtest=DemoSeedIdentityIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/identity",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.admin.properties.request.timeout.ms=500",
        "spring.kafka.admin.properties.default.api.timeout.ms=500",
        "app.internal-api-key=test-internal-key",
        "spring.autoconfigure.exclude=lms.demo.runner.DemoSeedAutoConfiguration",
        "lms.demo-seed.enabled=true",
        "lms.demo-seed.password=Demo@Seed-12345"
})
@ActiveProfiles("demo-seed")
@Testcontainers
class DemoSeedIdentityIT {

    static final String PASSWORD = "Demo@Seed-12345";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @TestConfiguration
    @EnableConfigurationProperties(DemoSeedProperties.class)
    static class Config {
    }

    @Autowired DemoSeedProperties properties;
    @Autowired List<DemoSeedStep> steps;
    @Autowired ApplicationContext context;

    private DemoSeedRunner runner(String... profiles) {
        return new DemoSeedRunner(properties, Arrays.asList(profiles), steps, Clock.system(VN), context, code -> { });
    }

    private long count(String sql, Object... args) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres);
             var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    @Test
    void seedIdentity_taoDuDuLieuTatDinhVaChayLaiKhongNhanDoi() throws Exception {
        UUID tenantId = DemoIds.tenant();
        DemoPlan plan = DemoPlan.generate(LocalDate.now(VN), properties.seed());

        // cấu hình profile đúng như thiết kế: tạo tenant, không cần tenant-id
        assertThat(properties.createsTenant()).isTrue();
        assertThat(steps).extracting(DemoSeedStep::name).containsExactly("identity", "identity.platform");
        assertThat(steps.get(0).order()).isEqualTo(1);

        // guard: profile prod bị từ chối (mã 2), không ghi gì
        assertThat(runner("prod", "demo-seed").execute()).isEqualTo(DemoSeedRunner.EXIT_REFUSED);
        assertThat(count("SELECT count(*) FROM tenants WHERE code = 'demo'")).isZero();

        // lần 1
        assertThat(runner("demo-seed").execute()).isEqualTo(DemoSeedRunner.EXIT_OK);
        long users = count("SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?", tenantId);
        assertThat(users).isEqualTo(3 + plan.readers().size());

        assertThat(count("SELECT count(*) FROM tenants WHERE id = ? AND code = 'demo' AND status = 'ACTIVE'", tenantId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenant_modules WHERE tenant_id = ? AND enabled AND module IN ('LENDING','SALES')",
                tenantId)).isEqualTo(2);

        // hợp đồng ACTIVE, kết thúc trong khoảng 20 ngày, đã áp dụng module
        assertThat(count("SELECT count(*) FROM tenant_contracts WHERE tenant_id = ? AND status = 'ACTIVE' "
                + "AND modules_applied_at IS NOT NULL AND ends_at > now() AND ends_at <= now() + interval '21 days'",
                tenantId)).isEqualTo(1);

        // id tất định + mật khẩu không buộc đổi
        for (var staff : plan.staff()) {
            assertThat(count("SELECT count(*) FROM credentials WHERE user_id = ? AND username = ? "
                    + "AND NOT must_change_password", staff.id(), staff.username())).as(staff.username()).isEqualTo(1);
        }
        for (var reader : plan.readers()) {
            assertThat(count("SELECT count(*) FROM users WHERE id = ? AND email = ?", reader.id(), reader.email()))
                    .as(reader.username()).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM reader_profiles WHERE tenant_id = ?", tenantId))
                .isEqualTo(plan.readers().size());
        // thông tin cá nhân: độc giả có điện thoại, khoa, mã SV/CB; nhân sự có chức danh
        assertThat(count("SELECT count(*) FROM reader_profiles WHERE tenant_id = ? AND phone IS NOT NULL "
                + "AND faculty IS NOT NULL AND member_code IS NOT NULL AND card_issued_date IS NOT NULL", tenantId))
                .isEqualTo(plan.readers().size());
        assertThat(count("SELECT count(*) FROM users WHERE id IN (?, ?, ?) AND job_title IS NOT NULL AND phone IS NOT NULL",
                plan.staff().get(0).id(), plan.staff().get(1).id(), plan.staff().get(2).id())).isEqualTo(3);

        // role: admin tenant giữ role isAdmin, thủ thư LIBRARIAN, quản lý cửa hàng STORE_MANAGER
        assertThat(count("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE ur.tenant_id = ? AND ur.user_id = ? AND r.is_admin", tenantId, plan.staff().get(0).id()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE ur.tenant_id = ? AND ur.user_id = ? AND r.name = 'LIBRARIAN'", tenantId, plan.staff().get(1).id()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE ur.tenant_id = ? AND ur.user_id = ? AND r.name = 'STORE_MANAGER'", tenantId, plan.staff().get(2).id()))
                .isEqualTo(1);

        long credentials = count("SELECT count(*) FROM credentials");
        long contracts = count("SELECT count(*) FROM tenant_contracts WHERE tenant_id = ?", tenantId);
        long roleRows = count("SELECT count(*) FROM user_roles WHERE tenant_id = ?", tenantId);
        long cards = count("SELECT count(*) FROM reader_profiles");

        // phần nền tảng: thanh toán hợp đồng, storefront origins (một đã tắt), Application (PUBLIC_SITE có domain, INTEGRATION có credential)
        assertThat(count("SELECT count(*) FROM tenant_contract_payments WHERE tenant_id = ?", tenantId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM tenant_storefront_origins WHERE tenant_id = ? AND NOT enabled", tenantId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenant_storefront_origins WHERE tenant_id = ? AND enabled", tenantId))
                .isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT count(*) FROM applications WHERE tenant_id = ?", tenantId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM application_credentials WHERE tenant_id = ?", tenantId)).isEqualTo(2);
        long payments = count("SELECT count(*) FROM tenant_contract_payments WHERE tenant_id = ?", tenantId);
        long origins = count("SELECT count(*) FROM tenant_storefront_origins WHERE tenant_id = ?", tenantId);
        long applications = count("SELECT count(*) FROM applications WHERE tenant_id = ?", tenantId);
        long appCredentials = count("SELECT count(*) FROM application_credentials WHERE tenant_id = ?", tenantId);

        // lần 2: không lỗi, không nhân đôi bất cứ thứ gì
        assertThat(runner("demo-seed").execute()).isEqualTo(DemoSeedRunner.EXIT_OK);
        assertThat(count("SELECT count(*) FROM tenants WHERE code = 'demo'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?", tenantId)).isEqualTo(users);
        assertThat(count("SELECT count(*) FROM credentials")).isEqualTo(credentials);
        assertThat(count("SELECT count(*) FROM tenant_contracts WHERE tenant_id = ?", tenantId)).isEqualTo(contracts);
        assertThat(count("SELECT count(*) FROM user_roles WHERE tenant_id = ?", tenantId)).isEqualTo(roleRows);
        assertThat(count("SELECT count(*) FROM reader_profiles")).isEqualTo(cards);
        assertThat(count("SELECT count(*) FROM tenant_contract_payments WHERE tenant_id = ?", tenantId)).isEqualTo(payments);
        assertThat(count("SELECT count(*) FROM tenant_storefront_origins WHERE tenant_id = ?", tenantId)).isEqualTo(origins);
        assertThat(count("SELECT count(*) FROM applications WHERE tenant_id = ?", tenantId)).isEqualTo(applications);
        assertThat(count("SELECT count(*) FROM application_credentials WHERE tenant_id = ?", tenantId))
                .isEqualTo(appCredentials);
    }
}
