package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.sales.contact.application.AnonymizeExpiredShopContactsUseCase;
import lms.sales.contact.application.AnonymizeShopContactBatchUseCase;
import lms.sales.contact.application.ShopContactQueryUseCases;
import lms.sales.contact.application.ShopContactRepository;
import lms.sales.contact.application.ShopContactWorkflowUseCases;
import lms.sales.contact.application.SubmitShopContactUseCase;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.contact.infrastructure.config.ShopContactAnonymizationJob;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.settings.application.UpdateSalesSettingsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.messaging.SalesEvent;
import lms.sales.shared.infrastructure.messaging.SalesEventPublisher;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T3, T6 (ADR-0015) bằng vai trò sales_app (NOSUPERUSER NOBYPASSRLS) trên PostgreSQL thật: cách ly tenant (IDOR), RLS
 * ENABLE+FORCE đúng một policy, các CHECK, danh bạ tenant khi tạo (AC12), giới hạn 429, job ẩn danh hai tenant khác
 * thời hạn bằng đồng hồ giả, PII không vào log/audit/sales.events. Cần Docker:
 * mvn -o -pl services/sales-service -am test -Dtest=ShopContactIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "lms.sales.anonymize.enabled=false"})
@Testcontainers
class ShopContactIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    private static final String NAME = "Nguyễn Văn Bí-Mật";
    private static final String EMAIL = "bi.mat@example.vn";
    private static final String PHONE = "0987654321";
    private static final String TEXT = "Noi dung rieng tu 7f3a";

    @Autowired SubmitShopContactUseCase submit;
    @Autowired ShopContactQueryUseCases queries;
    @Autowired ShopContactWorkflowUseCases workflow;
    @Autowired AnonymizeShopContactBatchUseCase batch;
    @Autowired GetSalesSettingsUseCase getSettings;
    @Autowired UpdateSalesSettingsUseCase updateSettings;
    @Autowired TenantDirectoryPort tenantDirectory;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean SalesEventPublisher eventPublisher;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID staff = UUID.randomUUID();

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    // ------------------------------------------------------------------ tiện ích

    private SubmitShopContactUseCase.Outcome send(UUID tenant, UUID user, String message) {
        return TenantContext.callAs(tenant, () -> submit.execute(new SubmitShopContactUseCase.Command(tenant, user,
                ContactTopic.PAYMENT, NAME, EMAIL, PHONE, message, true)));
    }

    private UUID idOf(UUID tenant, UUID user) throws SQLException {
        return UUID.fromString(text("select id::text from shop_contact_requests where tenant_id = '" + tenant
                + "' and customer_user_id = '" + user + "' order by created_at desc limit 1"));
    }

    private String text(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private long scalar(String sql) throws SQLException {
        return Long.parseLong(text(sql));
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static ShopContactRepository.SearchQuery all() {
        return new ShopContactRepository.SearchQuery(null, null, null, null, 0, 100);
    }

    // ------------------------------------------------------------------ cách ly tenant, IDOR

    @Test
    void guiThanhCongGhiDungTenantNguoiGuiVaDanhBaTenant() throws Exception {
        UUID user = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        assertThat(send(fresh, user, TEXT)).isEqualTo(SubmitShopContactUseCase.Outcome.CREATED);
        assertThat(scalar("select count(*) from shop_contact_requests where tenant_id = '" + fresh
                + "' and customer_user_id = '" + user + "' and status = 'NEW' and version = 0")).isEqualTo(1);
        // AC12: tenant chỉ có liên hệ, chưa có đơn nào, vẫn có trong danh bạ để job xử lý
        assertThat(tenantDirectory.listAll()).contains(fresh);
        assertThat(scalar("select count(*) from sales_orders where tenant_id = '" + fresh + "'")).isZero();
        // gửi trùng không ghi thêm
        assertThat(send(fresh, user, TEXT)).isEqualTo(SubmitShopContactUseCase.Outcome.DUPLICATE);
        assertThat(scalar("select count(*) from shop_contact_requests where tenant_id = '" + fresh + "'"))
                .isEqualTo(1);
    }

    @Test
    void khachKhongLayDuocLienHeCuaKhachKhacVaTenantKhacKhongThayDong() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        send(tenantA, userA, "ban A " + userA);
        send(tenantB, userB, "ban B " + userB);
        UUID idA = idOf(tenantA, userA);
        UUID idB = idOf(tenantB, userB);

        // nhân viên tenant A lấy/đổi/ẩn danh id của tenant B: 404 cùng mã như id không tồn tại
        for (Runnable attack : List.<Runnable>of(
                () -> TenantContext.runAs(tenantA, () -> queries.get(tenantA, idB)),
                () -> TenantContext.runAs(tenantA, () -> queries.contact(tenantA, idB)),
                () -> TenantContext.runAs(tenantA, () -> workflow.changeStatus(tenantA, staff, idB,
                        ContactStatus.CLOSED, 0)),
                () -> TenantContext.runAs(tenantA, () -> workflow.anonymize(tenantA, staff, idB)),
                () -> TenantContext.runAs(tenantA, () -> queries.get(tenantA, UUID.randomUUID())))) {
            assertThatThrownBy(attack::run).isInstanceOfSatisfying(SalesException.class,
                    e -> assertThat(e.code()).isEqualTo(ErrorCode.SHOP_CONTACT_NOT_FOUND));
        }
        // tenantId lấy từ token bị giả sang tenant khác: RLS vẫn chặn (đặt TenantContext A, hỏi tenant B)
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> queries.get(tenantB, idB)))
                .isInstanceOf(SalesException.class);
        assertThat(scalar("select count(*) from shop_contact_requests where id = '" + idB
                + "' and pii_anonymized_at is null and status = 'NEW'")).isEqualTo(1);

        // search chỉ thấy tenant mình
        var pageA = TenantContext.callAs(tenantA, () -> queries.search(tenantA, all()));
        assertThat(pageA.items()).extracting(ShopContactRequest::id).contains(idA).doesNotContain(idB);
        assertThat(pageA.items()).allMatch(r -> r.tenantId().equals(tenantA));
        // mỗi tenant thấy đúng dòng của mình
        assertThat(TenantContext.callAs(tenantB, () -> queries.get(tenantB, idB)).customerUserId()).isEqualTo(userB);
    }

    @Test
    void doiTrangThaiVersionLechLa409VaClosedAtKhop() throws Exception {
        UUID user = UUID.randomUUID();
        send(tenantA, user, "workflow " + user);
        UUID id = idOf(tenantA, user);
        var r0 = TenantContext.callAs(tenantA, () -> queries.get(tenantA, id));
        var r1 = TenantContext.callAs(tenantA, () -> workflow.changeStatus(tenantA, staff, id,
                ContactStatus.IN_PROGRESS, r0.version()));
        assertThat(r1.version()).isEqualTo(r0.version() + 1);
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> workflow.changeStatus(tenantA, staff, id,
                ContactStatus.CLOSED, r0.version()))).isInstanceOfSatisfying(SalesException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SHOP_CONTACT_STATE_CONFLICT));
        var closed = TenantContext.callAs(tenantA, () -> workflow.changeStatus(tenantA, staff, id,
                ContactStatus.CLOSED, r1.version()));
        assertThat(closed.closedAt()).isNotNull();
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> workflow.changeStatus(tenantA, staff, id,
                ContactStatus.IN_PROGRESS, closed.version()))).isInstanceOfSatisfying(SalesException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SHOP_CONTACT_STATE_CONFLICT));
    }

    @Test
    void anDanhNgayXoaNamCotVaVanDoiTrangThaiDuoc() throws Exception {
        UUID user = UUID.randomUUID();
        send(tenantA, user, "an danh ngay " + user);
        UUID id = idOf(tenantA, user);
        var a = TenantContext.callAs(tenantA, () -> workflow.anonymize(tenantA, staff, id));
        assertThat(a.piiAnonymizedAt()).isNotNull();
        assertThat(scalar("select count(*) from shop_contact_requests where id = '" + id
                + "' and customer_user_id is null and message is null and contact_name is null "
                + "and contact_email is null and contact_phone is null and topic = 'PAYMENT'")).isEqualTo(1);
        var again = TenantContext.callAs(tenantA, () -> workflow.anonymize(tenantA, staff, id));
        assertThat(again.piiAnonymizedAt()).isEqualTo(a.piiAnonymizedAt());
        var moved = TenantContext.callAs(tenantA, () -> workflow.changeStatus(tenantA, staff, id,
                ContactStatus.CLOSED, again.version()));
        assertThat(moved.status()).isEqualTo(ContactStatus.CLOSED);
        assertThat(moved.isAnonymized()).isTrue();
    }

    // ------------------------------------------------------------------ RLS và CHECK (AC15)

    @Test
    void rlsEnableForceDungMotPolicyTenantIsolationVaAppRoleKhongDocKhiChuaDatTenant() throws Exception {
        UUID user = UUID.randomUUID();
        send(tenantA, user, "rls " + user);
        assertThat(text("select relrowsecurity || '|' || relforcerowsecurity from pg_class "
                + "where relname = 'shop_contact_requests'")).isEqualTo("true|true");
        assertThat(text("select string_agg(policyname, ',') from pg_policies "
                + "where tablename = 'shop_contact_requests'")).isEqualTo("tenant_isolation");
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from shop_contact_requests")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero(); // chưa đặt app.tenant_id: fail-closed
        }
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement()) {
            s.execute("select set_config('app.tenant_scope', 'all', false)");
            try (ResultSet rs = s.executeQuery("select count(*) from shop_contact_requests")) {
                rs.next();
                assertThat(rs.getLong(1)).as("không có platform_read_all").isZero();
            }
        }
    }

    @Test
    void checkChanDongAnDanhThieuCotVaClosedAtLech() {
        String base = "insert into shop_contact_requests (id, tenant_id, customer_user_id, topic, message, "
                + "contact_name, contact_email, consent_at, status, created_at, closed_at, pii_anonymized_at) values ";
        String t = "'" + tenantA + "'";
        String u = "'" + UUID.randomUUID() + "'";
        // đã ẩn danh nhưng còn message
        assertThatThrownBy(() -> exec(base + "(gen_random_uuid(), " + t + ", null, 'OTHER', 'con', null, null, now(), "
                + "'NEW', now(), null, now())")).hasMessageContaining("ck_shop_contact_pii_consistency");
        // chưa ẩn danh nhưng thiếu email
        assertThatThrownBy(() -> exec(base + "(gen_random_uuid(), " + t + ", " + u + ", 'OTHER', 'm', 'n', null, now(), "
                + "'NEW', now(), null, null)")).hasMessageContaining("ck_shop_contact_pii_consistency");
        // CLOSED không có closed_at
        assertThatThrownBy(() -> exec(base + "(gen_random_uuid(), " + t + ", " + u + ", 'OTHER', 'm', 'n', 'e@x.vn', "
                + "now(), 'CLOSED', now(), null, null)")).hasMessageContaining("ck_shop_contact_closed_at");
        // topic/status lạ, message rỗng
        assertThatThrownBy(() -> exec(base + "(gen_random_uuid(), " + t + ", " + u + ", 'LA', 'm', 'n', 'e@x.vn', "
                + "now(), 'NEW', now(), null, null)")).hasMessageContaining("ck_shop_contact_topic");
        assertThatThrownBy(() -> exec(base + "(gen_random_uuid(), " + t + ", " + u + ", 'OTHER', '  ', 'n', 'e@x.vn', "
                + "now(), 'NEW', now(), null, null)")).hasMessageContaining("ck_shop_contact_message_not_blank");
    }

    // ------------------------------------------------------------------ giới hạn spam (AC7)

    @Test
    void yeuCauThuSauCuaCungNguoiLa429NguoiKhacCungTenantKhongBiAnhHuong() {
        UUID user = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            assertThat(send(tenantA, user, "spam " + i)).isEqualTo(SubmitShopContactUseCase.Outcome.CREATED);
        }
        assertThatThrownBy(() -> send(tenantA, user, "spam 6")).isInstanceOfSatisfying(SalesException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.SHOP_CONTACT_LIMIT_EXCEEDED));
        assertThat(send(tenantA, UUID.randomUUID(), "spam 6")).isEqualTo(SubmitShopContactUseCase.Outcome.CREATED);
        // bấm lại yêu cầu thứ 5 vẫn là gửi lại (R4 trước R5)
        assertThat(send(tenantA, user, "spam 5")).isEqualTo(SubmitShopContactUseCase.Outcome.DUPLICATE);
    }

    // ------------------------------------------------------------------ job ẩn danh theo hạn từng tenant (AC11)

    @Test
    void jobAnDanhHaiTenantThoiHanKhacNhauBangDongHoGiaChayLaiKhongDoiGi() throws Exception {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        TenantContext.runAs(t1, () -> updateSettings.execute(new UpdateSalesSettingsCommand(t1, staff, 500, true, 7,
                0, 5, 6, getSettings.execute(t1).version())));
        TenantContext.runAs(t2, () -> updateSettings.execute(new UpdateSalesSettingsCommand(t2, staff, 500, true, 7,
                0, 5, 24, getSettings.execute(t2).version())));
        Instant now = Instant.parse("2026-11-17T03:00:00Z");
        // tạo bằng use case rồi lùi created_at (superuser) để mô phỏng tuổi dòng
        for (UUID[] p : List.of(new UUID[]{t1, u1}, new UUID[]{t2, u2})) {
            send(p[0], p[1], "cu " + p[1]);
        }
        UUID oldT1 = idOf(t1, u1);
        UUID oldT2 = idOf(t2, u2);
        send(t1, UUID.randomUUID(), "moi t1"); // dòng mới của t1: giữ
        send(t2, UUID.randomUUID(), "giua t2"); // 15 tháng: t2 giữ 24 tháng
        UUID midT2 = UUID.fromString(text("select id::text from shop_contact_requests where tenant_id = '" + t2
                + "' and customer_user_id <> '" + u2 + "'"));
        exec("update shop_contact_requests set created_at = '2026-03-01T00:00:00Z' where id = '" + oldT1 + "'"); // 8 tháng
        exec("update shop_contact_requests set created_at = '2024-01-01T00:00:00Z' where id = '" + oldT2 + "'"); // >24
        exec("update shop_contact_requests set created_at = '2025-08-01T00:00:00Z' where id = '" + midT2 + "'");
        exec("update shop_contact_requests set status = 'IN_PROGRESS' where id = '" + oldT1 + "'"); // mọi trạng thái

        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
        var expire = new AnonymizeExpiredShopContactsUseCase(getSettings, batch, fixed, 500);
        var failing = new AnonymizeExpiredShopContactsUseCase(getSettings, batch, fixed, 500) {
            @Override
            public int execute(UUID tenantId) {
                if (tenantId.equals(tenantA)) {
                    throw new IllegalStateException("loi gia lap " + EMAIL);
                }
                return super.execute(tenantId);
            }
        };
        // tenantA có trong danh bạ (đã gửi ở test khác hoặc chèn ở đây) để chứng minh lỗi một tenant không chặn tenant khác
        TenantContext.runAs(tenantA, () -> send(tenantA, UUID.randomUUID(), "de co trong danh ba"));
        var job = new ShopContactAnonymizationJob(tenantDirectory, failing);
        job.runOnce();

        assertThat(text("select pii_anonymized_at is not null from shop_contact_requests where id = '" + oldT1 + "'"))
                .isEqualTo("t");
        assertThat(text("select pii_anonymized_at is not null from shop_contact_requests where id = '" + oldT2 + "'"))
                .isEqualTo("t");
        assertThat(text("select pii_anonymized_at is null from shop_contact_requests where id = '" + midT2 + "'"))
                .isEqualTo("t");
        assertThat(scalar("select count(*) from shop_contact_requests where tenant_id = '" + t1
                + "' and pii_anonymized_at is not null")).isEqualTo(1);
        // trạng thái và chủ đề giữ nguyên, năm cột PII null
        assertThat(text("select status || '|' || topic || '|' || (message is null and contact_name is null "
                + "and contact_email is null and contact_phone is null and customer_user_id is null) "
                + "from shop_contact_requests where id = '" + oldT1 + "'")).isEqualTo("IN_PROGRESS|PAYMENT|true");
        long audits = scalar("select count(*) from sales_audit_log where action = 'SHOP_CONTACT_PII_ANONYMIZED' "
                + "and tenant_id in ('" + t1 + "', '" + t2 + "')");
        assertThat(audits).isEqualTo(2);

        // chạy lại không đổi gì (idempotent), kể cả bằng use case thường
        assertThat(TenantContext.callAs(t1, () -> expire.execute(t1))).isZero();
        assertThat(TenantContext.callAs(t2, () -> expire.execute(t2))).isZero();
        assertThat(scalar("select count(*) from sales_audit_log where action = 'SHOP_CONTACT_PII_ANONYMIZED' "
                + "and tenant_id in ('" + t1 + "', '" + t2 + "')")).isEqualTo(2);
        // log của job chỉ có mã tenant/số đếm, không PII
        assertNoPiiInLogs();
    }

    // ------------------------------------------------------------------ PII không vào log/audit/sales.events (AC13)

    @Test
    void piiKhongVaoLogAuditHayEventTrongMoiNhanh() throws Exception {
        UUID user = UUID.randomUUID();
        send(tenantA, user, TEXT + " " + user);
        send(tenantA, user, TEXT + " " + user); // trùng
        UUID id = idOf(tenantA, user);
        var r = TenantContext.callAs(tenantA, () -> queries.get(tenantA, id));
        TenantContext.runAs(tenantA, () -> workflow.changeStatus(tenantA, staff, id, ContactStatus.IN_PROGRESS,
                r.version())); // 200
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> workflow.changeStatus(tenantA, staff, id,
                ContactStatus.NEW, r.version()))).isInstanceOf(SalesException.class); // 409
        assertThatThrownBy(() -> TenantContext.runAs(tenantA, () -> submit.execute(new SubmitShopContactUseCase.Command(
                tenantA, user, ContactTopic.OTHER, NAME, "sai-dinh-dang", PHONE, TEXT, true))))
                .isInstanceOf(SalesException.class); // 400
        TenantContext.runAs(tenantA, () -> workflow.anonymize(tenantA, staff, id));

        // audit trong DB: chỉ khóa cho phép, actor null với SHOP_CONTACT_CREATED
        assertThat(scalar("select count(*) from sales_audit_log where aggregate_id = '" + id + "'")).isEqualTo(3);
        assertThat(text("select string_agg(data::text || '|' || coalesce(actor_id::text, 'null'), ';' "
                + "order by occurred_at, action) from sales_audit_log where aggregate_id = '" + id + "'"))
                .doesNotContain(NAME).doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain("Noi dung")
                .doesNotContain("Mật").doesNotContain(user.toString());
        assertThat(text("select actor_id is null from sales_audit_log where aggregate_id = '" + id
                + "' and action = 'SHOP_CONTACT_CREATED'")).isEqualTo("t");
        assertThat(text("select (data - 'topic') = '{}'::jsonb from sales_audit_log where aggregate_id = '" + id
                + "' and action = 'SHOP_CONTACT_CREATED'")).isEqualTo("t");

        // payload sales.events (bắt ở publisher giả)
        var captor = forClass(SalesEvent.class);
        verify(eventPublisher, atLeast(1)).publishAfterCommit(captor.capture());
        for (SalesEvent e : captor.getAllValues()) {
            if ("ShopContactRequest".equals(e.aggregateType())) {
                assertThat(e.toString()).doesNotContain(NAME).doesNotContain(EMAIL).doesNotContain(PHONE)
                        .doesNotContain("Noi dung").doesNotContain(user.toString());
                if ("SHOP_CONTACT_CREATED".equals(e.action())) {
                    assertThat(e.actorId()).isNull();
                    assertThat(e.data()).containsOnlyKeys("topic");
                }
            }
        }
        assertNoPiiInLogs();
    }

    private void assertNoPiiInLogs() {
        List<String> all = new ArrayList<>();
        for (ILoggingEvent e : new ArrayList<>(logs.list)) {
            all.add(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
                all.add(e.getThrowableProxy().getMessage());
            }
        }
        String joined = String.join("\n", all);
        assertThat(joined).doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain("Noi dung rieng tu")
                .doesNotContain("Bí-Mật").doesNotContain("sai-dinh-dang");
    }
}
