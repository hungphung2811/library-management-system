package lms.support.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lms.database.core.DatabaseConnectionRegistry;
import lms.support.audit.integration.AppRoleDatabase;
import lms.support.notification.application.DeliveryPort;
import lms.support.notification.application.DeliveryPort.DeliveryCriteria;
import lms.support.notification.application.DeliveryUseCases;
import lms.support.notification.application.DispatchNotificationsUseCase;
import lms.support.notification.application.EnqueueNotificationUseCase;
import lms.support.notification.application.NotificationSettingsUseCases;
import lms.support.notification.application.NotificationTemplateUseCases;
import lms.support.notification.application.NotificationTrigger;
import lms.support.notification.application.RecipientDirectoryPort;
import lms.support.notification.application.RecipientDirectoryPort.Lookup;
import lms.support.notification.application.RecipientDirectoryPort.Recipient;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.infrastructure.persistence.DeliveryPersistenceAdapter;
import lms.tenant.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Thông báo theo sự kiện trên PostgreSQL thật, kết nối bằng vai trò audit_app (NOBYPASSRLS) nên RLS có hiệu lực
 * (ADR-0030 gói C): khử trùng khi giao lại/chạy song song, cách ly hai tenant cho deliveries/templates/preferences/
 * channel settings, khóa bỏ qua dòng đã khóa (SKIP LOCKED) giữa hai instance, đếm giới hạn tốc độ xuyên tenant. Cần Docker.
 * Chạy: mvn test -Dtest=NotificationDeliveryIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Tag("docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "app.security.enabled=false",
        "lms.notification.dispatch.enabled=false"
})
@Testcontainers
class NotificationDeliveryIT {

    // Mỗi test (mỗi instance) dùng tenant riêng: DB Testcontainers dùng chung cả lớp nên cấu hình/lịch sử của test này
    // (tắt kênh, mẫu ghi đè, hàng chờ) không được lọt sang test khác.
    final UUID TENANT_A = UUID.randomUUID();
    final UUID TENANT_B = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void databases(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
        registry.add("database.connections.LOG_DB.uri", () -> mongo.getReplicaSetUrl("audit_logs"));
    }

    @TestConfiguration
    static class AuditFlywayTestConfiguration {
        @Bean(name = "auditFlyway", initMethod = "migrate")
        @DependsOn("databaseLifecycleManager")
        Flyway auditFlyway(DatabaseConnectionRegistry registry) {
            return Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration/audit")
                    .load();
        }
    }

    @MockitoBean JavaMailSender mail;
    @MockitoBean RecipientDirectoryPort directory;

    @Autowired EnqueueNotificationUseCase enqueue;
    @Autowired DispatchNotificationsUseCase dispatch;
    @Autowired DeliveryUseCases deliveries;
    @Autowired NotificationSettingsUseCases settings;
    @Autowired NotificationTemplateUseCases templates;
    @Autowired DeliveryPersistenceAdapter deliveryPort;
    @Autowired @Qualifier("auditDbTransactionManager") PlatformTransactionManager txManager;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private JdbcTemplate superuserJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()));
    }

    private void stubMail() throws Exception {
        when(mail.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private NotificationTrigger trigger(UUID eventId, UUID user) {
        return NotificationTrigger.forUser(eventId, "ReservationReadyEvent", NotificationType.RESERVATION_READY, user,
                Map.of("hold_until", "04/01/2026"));
    }

    private int enqueueAs(UUID tenant, NotificationTrigger t) {
        return TenantContext.callAs(tenant, () -> enqueue.execute(t));
    }

    private long countDeliveries(UUID tenant) {
        return TenantContext.callAs(tenant,
                () -> deliveries.search(new DeliveryCriteria(null, null, null, null, null, 0, 100)).total());
    }

    // ---- khử trùng ----

    @Test
    void giaoLaiBaLanVaChaySongSongChiRaMotDeliveryVaMotThu() throws Exception {
        stubMail();
        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(directory.lookup(any(), any())).thenReturn(new Lookup("Hiệu sách A",
                Map.of(user, new Recipient(user, "doc.gia@example.com", "Độc Giả", true))));

        assertThat(enqueueAs(TENANT_A, trigger(eventId, user))).isEqualTo(1);
        assertThat(enqueueAs(TENANT_A, trigger(eventId, user))).isZero();
        assertThat(enqueueAs(TENANT_A, trigger(eventId, user))).isZero();

        // Hai instance cùng nhận một message cùng lúc
        UUID raceEvent = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return enqueueAs(TENANT_A, trigger(raceEvent, user));
            }));
        }
        start.countDown();
        int inserted = 0;
        for (Future<Integer> f : futures) inserted += f.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(inserted).isEqualTo(1);

        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM notification_deliveries WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM notification_deliveries WHERE event_id = ?",
                Integer.class, raceEvent)).isEqualTo(1);
        // Thông báo trong ứng dụng cũng khử trùng
        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?",
                Integer.class, user)).isEqualTo(2);

        dispatch.dispatchTenant(TENANT_A, 100);
        verify(mail, atLeastOnce()).send(any(MimeMessage.class));
        assertThat(superuserJdbc().queryForObject(
                "SELECT COUNT(*) FROM notification_deliveries WHERE recipient_user_id = ? AND status = 'SENT'",
                Integer.class, user)).isEqualTo(2);
        // Địa chỉ đầy đủ không lưu; chỉ dạng che
        assertThat(superuserJdbc().queryForObject(
                "SELECT recipient_address_masked FROM notification_deliveries WHERE event_id = ?", String.class, eventId))
                .isEqualTo("d***@example.com");
        assertThat(superuserJdbc().queryForObject(
                "SELECT COUNT(*) FROM notification_deliveries WHERE recipient_address IS NOT NULL", Integer.class)).isZero();
        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM notification_tenants WHERE tenant_id = ?",
                Integer.class, TENANT_A)).isEqualTo(1);
    }

    @Test
    void nguoiNhanTrucTiepLuuDiaChiTamVaXoaKhiKetThuc() throws Exception {
        stubMail();
        when(directory.lookup(any(), any())).thenReturn(new Lookup("Hiệu sách A", Map.of()));
        UUID eventId = UUID.randomUUID();
        TenantContext.runAs(TENANT_B, () -> enqueue.execute(NotificationTrigger.forAddress(eventId, "NotificationDirect",
                NotificationType.INVOICE_ISSUED, "khach.le@example.com", Map.of("invoice_no", "C26TAA-000001"))));
        assertThat(superuserJdbc().queryForObject(
                "SELECT recipient_address FROM notification_deliveries WHERE event_id = ?", String.class, eventId))
                .isEqualTo("khach.le@example.com");
        dispatch.dispatchTenant(TENANT_B, 100);
        assertThat(superuserJdbc().queryForObject(
                "SELECT status || '/' || COALESCE(recipient_address, 'NULL') FROM notification_deliveries WHERE event_id = ?",
                String.class, eventId)).isEqualTo("SENT/NULL");
    }

    // ---- RLS ----

    @Test
    void deliveriesCuaTenantBKhongThayTuTenantAVaFailClosedKhongTenant() throws Exception {
        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        enqueueAs(TENANT_A, trigger(eventId, user));

        assertThat(TenantContext.callAs(TENANT_A, () -> deliveries.mine(user))).hasSize(1);
        assertThat(TenantContext.callAs(TENANT_B, () -> deliveries.mine(user))).isEmpty();
        assertThat(countDeliveries(TENANT_B)).isZero();
        UUID id = superuserJdbc().queryForObject("SELECT id FROM notification_deliveries WHERE event_id = ?", UUID.class,
                eventId);
        // Gửi lại lượt của tenant khác: không thấy (404), không phải 409
        assertThatThrownBy(() -> TenantContext.runAs(TENANT_B, () -> deliveries.retry(id, UUID.randomUUID())))
                .isInstanceOf(lms.support.notification.domain.DeliveryNotFoundException.class);

        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), AppRoleDatabase.APP_ROLE,
                AppRoleDatabase.APP_ROLE); Statement s = c.createStatement()) {
            // Không đặt app.tenant_id: có dữ liệu nhưng không thấy dòng nào ở mọi bảng thông báo có RLS
            for (String table : new String[] {"notification_deliveries", "notification_templates",
                    "notification_channel_settings", "notification_preferences"}) {
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rs.next();
                    assertThat(rs.getInt(1)).as(table).isZero();
                }
            }
            // WITH CHECK: đặt tenant A nhưng chèn dòng của tenant B bị từ chối
            s.execute("SELECT set_config('app.tenant_id', '" + TENANT_A + "', false)");
            assertThatThrownBy(() -> s.execute("INSERT INTO notification_deliveries (id, tenant_id, event_id, event_type, "
                    + "notification_type, category, channel, recipient_key, status, next_attempt_at, created_at, updated_at) "
                    + "VALUES ('" + UUID.randomUUID() + "', '" + TENANT_B + "', '" + UUID.randomUUID()
                    + "', 'E', 'ORDER_PAID', 'ORDER', 'EMAIL', 'k', 'PENDING', now(), now(), now())"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void mauKenhVaTuyChonCuaTenantNaoChiAnhHuongTenantDo() {
        UUID user = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        TenantContext.runAs(TENANT_A, () -> {
            templates.save(NotificationType.ORDER_PAID, NotificationChannel.EMAIL, "Đã thu {{order_no}}", "Cảm ơn", admin);
            templates.save(NotificationType.ORDER_PAID, NotificationChannel.EMAIL, "Đã thu tiền {{order_no}}", "Cảm ơn", admin);
            settings.updateChannels(List.of(new NotificationSettingsUseCases.ChannelSettingChange(
                    NotificationType.RESERVATION_READY, NotificationChannel.EMAIL, false)), admin);
            settings.updatePreferences(user, List.of(new NotificationSettingsUseCases.PreferenceChange(
                    NotificationCategory.FINE, NotificationChannel.EMAIL, false)));
        });
        TenantContext.runAs(TENANT_A, () -> {
            var t = templates.get(NotificationType.ORDER_PAID, NotificationChannel.EMAIL).orElseThrow();
            assertThat(t.source()).isEqualTo("TENANT");
            assertThat(t.version()).isEqualTo(2);
            assertThat(t.subject()).isEqualTo("Đã thu tiền {{order_no}}");
            assertThat(settings.channels()).filteredOn(c -> c.type().equals("RESERVATION_READY")).allMatch(c -> !c.enabled());
            assertThat(settings.preferences(user)).filteredOn(p -> p.category().equals("FINE")).allMatch(p -> !p.enabled());
        });
        TenantContext.runAs(TENANT_B, () -> {
            assertThat(templates.get(NotificationType.ORDER_PAID, NotificationChannel.EMAIL).orElseThrow().source())
                    .isEqualTo("DEFAULT");
            assertThat(settings.channels()).allMatch(c -> c.enabled());
            assertThat(settings.preferences(user)).allMatch(p -> p.enabled());
        });
        // Chỉ một phiên bản active mỗi (tenant, loại, kênh) và phiên bản cũ vẫn còn để truy vết
        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM notification_templates WHERE tenant_id = ? "
                + "AND active", Integer.class, TENANT_A)).isEqualTo(1);
        assertThat(superuserJdbc().queryForObject("SELECT COUNT(*) FROM notification_templates WHERE tenant_id = ?",
                Integer.class, TENANT_A)).isEqualTo(2);
    }

    // ---- khóa dòng giữa hai instance ----

    @Test
    void layLoBoQuaDongDangBiInstanceKhacKhoa() throws Exception {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID tenant = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
        TenantContext.runAs(tenant, () -> {
            enqueue.execute(trigger(e1, UUID.randomUUID()));
            enqueue.execute(trigger(e2, UUID.randomUUID()));
        });
        TransactionTemplate tx1 = new TransactionTemplate(txManager);
        TransactionTemplate tx2 = new TransactionTemplate(txManager);
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<List<UUID>> first = pool.submit(() -> TenantContext.callAs(tenant, () -> tx1.execute(status -> {
            List<UUID> ids = deliveryPort.leaseDue(1, Instant.now().plusSeconds(1)).stream().map(Delivery::eventId).toList();
            firstHolds.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ids;
        })));
        assertThat(firstHolds.await(30, TimeUnit.SECONDS)).isTrue();
        // Instance thứ hai chạy KHI instance đầu còn giữ khóa: không chờ, lấy dòng còn lại; lần sau không còn gì
        Future<List<List<UUID>>> second = pool.submit(() -> TenantContext.callAs(tenant, () -> tx2.execute(status -> {
            List<UUID> a = deliveryPort.leaseDue(1, Instant.now().plusSeconds(1)).stream().map(Delivery::eventId).toList();
            List<UUID> b = deliveryPort.leaseDue(5, Instant.now().plusSeconds(1)).stream().map(Delivery::eventId).toList();
            return List.of(a, b);
        })));
        List<List<UUID>> got = second.get(15, TimeUnit.SECONDS); // quá hạn nghĩa là bị chặn: SKIP LOCKED không hoạt động
        release.countDown();
        List<UUID> held = first.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(held).hasSize(1);
        assertThat(got.get(0)).hasSize(1).doesNotContainAnyElementsOf(held);
        assertThat(got.get(1)).as("hai dòng đã được hai instance lấy hết").isEmpty();
        assertThat(List.of(held.get(0), got.get(0).get(0))).containsExactlyInAnyOrder(e1, e2);
    }

    // ---- giới hạn tốc độ ----

    @Test
    void demThuDaGuiTheoTenantVaXuyenTenant() throws Exception {
        stubMail();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID t1 = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        UUID t2 = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
        when(directory.lookup(any(), any())).thenAnswer(i -> {
            Collection<UUID> ids = i.getArgument(1);
            java.util.HashMap<UUID, Recipient> m = new java.util.HashMap<>();
            for (UUID id : ids) m.put(id, new Recipient(id, id + "@example.com", "N", true));
            return new Lookup("Hiệu sách", m);
        });
        enqueueAs(t1, trigger(UUID.randomUUID(), userA));
        enqueueAs(t1, trigger(UUID.randomUUID(), userA));
        enqueueAs(t2, trigger(UUID.randomUUID(), userB));
        dispatch.dispatchTenant(t1, 100);
        dispatch.dispatchTenant(t2, 100);

        Instant since = Instant.now().minus(Duration.ofMinutes(1));
        assertThat(TenantContext.callAs(t1, () -> deliveryPort.countSentSince(since))).isEqualTo(2);
        assertThat(TenantContext.callAs(t2, () -> deliveryPort.countSentSince(since))).isEqualTo(1);
        assertThat(TenantContext.callAs(UUID.randomUUID(), () -> deliveryPort.countSentSince(since))).isZero();
        // Phạm vi ALL (policy platform_read_all) đếm cả hai tenant, chỉ đọc
        TenantContext.bindAllTenants();
        try {
            Long all = new TransactionTemplate(txManager).execute(s -> deliveryPort.countSentSinceAllTenants(since));
            assertThat(all).isNotNull().isGreaterThanOrEqualTo(3L);
        } finally {
            TenantContext.clear();
        }
        verify(mail, never()).send(any(org.springframework.mail.SimpleMailMessage.class));
    }
}
