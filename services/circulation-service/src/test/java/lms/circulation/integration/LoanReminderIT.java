package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.circulation.borrowing.application.SaveBorrowingPort;
import lms.circulation.borrowing.application.SendLoanReminderBatchUseCase;
import lms.circulation.borrowing.application.SendLoanRemindersUseCase;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.circulation.borrowing.domain.LoanDueSoon;
import lms.circulation.borrowing.domain.LoanOverdueReminder;
import lms.circulation.borrowing.infrastructure.messaging.KafkaBorrowingEventPublisher;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.common.kernel.DomainEvent;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Nhắc hạn trả (ADR-0030 D11) trên PostgreSQL thật, ứng dụng chạy bằng circulation_app (NOBYPASSRLS): quy tắc chọn phiếu
 * (sắp hạn, quá hạn, ân hạn, chu kỳ, trần, khử trùng, gia hạn đổi hạn nhắc lại), nhiều tenant độc lập, phát lỗi thì rollback
 * lô (không còn dòng loan_reminders). Kafka được giả (KafkaBorrowingEventPublisher là mock ghi sự kiện).
 * Cần Docker. Chạy: {@code -Dtest=LoanReminderIT -DargLine=-Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=it",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=200",
        "app.clients.catalog.base-url=http://localhost:1",
        "app.clients.identity.base-url=http://localhost:1",
        "app.reservation.expire-cron=-",
        "app.borrowing.reminder-cron=-"})
@Testcontainers
class LoanReminderIT {

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired SaveBorrowingPort borrowings;
    @Autowired SendLoanRemindersUseCase reminders;
    @Autowired SendLoanReminderBatchUseCase batches;
    @Autowired GetCirculationPolicyUseCase policies;
    @Autowired Clock clock;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean KafkaBorrowingEventPublisher publisher;

    final List<DomainEvent> events = new ArrayList<>();

    @BeforeEach
    void recordEvents() {
        reset(publisher);
        events.clear();
        doAnswer(i -> events.add(i.getArgument(0))).when(publisher).publishAndWait(any());
    }

    /** Phiếu của tenant với hạn trả = TODAY + dueOffset; returned = số bản sao đã trả (trả hết thì phiếu RETURNED). */
    private UUID loan(UUID tenant, int dueOffset, int copies, int returned) {
        return TenantContext.callAs(tenant, () -> {
            List<UUID> copyIds = new ArrayList<>();
            for (int i = 0; i < copies; i++) copyIds.add(UUID.randomUUID());
            LocalDate borrowDate = TODAY.minusDays(30);
            BorrowingRecord record = BorrowingRecord.borrow(UUID.randomUUID(), copyIds, borrowDate,
                    (int) (30 + dueOffset));
            for (int i = 0; i < returned; i++) {
                record.returnCopy(copyIds.get(i), ItemCondition.GOOD, borrowDate.plusDays(1));
            }
            borrowings.save(record);
            return record.id().value();
        });
    }

    private SendLoanRemindersUseCase.Summary run(UUID tenant) {
        return TenantContext.callAs(tenant, () -> reminders.execute(tenant));
    }

    private static void sql(String sql) {
        AppRoleDatabase.asSuperuser(postgres, sql);
    }

    private static long count(String where) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM loan_reminders WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<UUID> dueSoonIds() {
        return events.stream().filter(e -> e instanceof LoanDueSoon).map(e -> UUID.fromString(e.aggregateId())).toList();
    }

    private List<LoanOverdueReminder> overdueEvents() {
        return events.stream().filter(e -> e instanceof LoanOverdueReminder).map(e -> (LoanOverdueReminder) e).toList();
    }

    private void setPolicy(UUID tenant, int graceDays, int daysBefore, int interval, int max) {
        sql("INSERT INTO circulation_policies (id, tenant_id, version, loan_days, max_renewals, renewal_days, "
                + "max_active_loans_per_reader, fine_per_day, grace_days, reservation_hold_days, due_reminder_days_before, "
                + "overdue_reminder_interval_days, overdue_reminder_max, updated_at) VALUES ('" + UUID.randomUUID() + "', '"
                + tenant + "', 0, 14, 2, 14, 5, 5000, " + graceDays + ", 3, " + daysBefore + ", " + interval + ", " + max
                + ", now())");
    }

    @Test
    void sapDenHanChiChonPhieuTrongKhoangVaChuaTraHet() throws Exception {
        UUID t = UUID.randomUUID();
        UUID homNay = loan(t, 0, 1, 0);
        UUID mai = loan(t, 1, 1, 0);
        UUID haiNgay = loan(t, 2, 1, 0);
        UUID baNgay = loan(t, 3, 1, 0);          // ngoài khoảng mặc định 2 ngày
        UUID quaHan = loan(t, -1, 1, 0);          // không phải sắp hạn
        UUID daTraHet = loan(t, 1, 2, 2);

        SendLoanRemindersUseCase.Summary s = run(t);

        assertThat(dueSoonIds()).containsExactlyInAnyOrder(homNay, mai, haiNgay);
        assertThat(dueSoonIds()).doesNotContain(baNgay, quaHan, daTraHet);
        assertThat(s.dueSoon()).isEqualTo(3);
        assertThat(count("tenant_id = '" + t + "' AND kind = 'DUE_SOON'")).isEqualTo(3);
    }

    @Test
    void chayHaiLanCungNgayKhongNhacTrungVaSoCuonLaSoChuaTra() throws Exception {
        UUID t = UUID.randomUUID();
        UUID p = loan(t, 1, 3, 1); // 3 cuốn, đã trả 1 -> còn 2

        run(t);
        run(t);

        assertThat(events).hasSize(1);
        LoanDueSoon e = (LoanDueSoon) events.get(0);
        assertThat(e.borrowingId()).isEqualTo(p);
        assertThat(e.itemCount()).isEqualTo(2);
        assertThat(e.daysBefore()).isEqualTo(1);
        assertThat(count("borrowing_id = '" + p + "'")).isEqualTo(1);
    }

    @Test
    void giaHanDoiHanTraThiNhacLaiChoHanMoi() throws Exception {
        UUID t = UUID.randomUUID();
        UUID p = loan(t, 1, 1, 0);
        run(t);
        assertThat(events).hasSize(1);

        sql("UPDATE borrowings SET due_date = due_date + 14 WHERE id = '" + p + "'"); // gia hạn: hạn mới xa
        events.clear();
        run(t);
        assertThat(events).isEmpty(); // hạn mới 15 ngày nữa, chưa tới

        sql("UPDATE borrowings SET due_date = '" + TODAY.plusDays(2) + "' WHERE id = '" + p + "'");
        run(t);
        assertThat(dueSoonIds()).containsExactly(p); // hạn khác với lần nhắc cũ -> nhắc lại
        assertThat(count("borrowing_id = '" + p + "' AND kind = 'DUE_SOON'")).isEqualTo(2);
    }

    @Test
    void quaHanNhacTheoChuKyVaDungKhiDuTran() throws Exception {
        UUID t = UUID.randomUUID();
        UUID p = loan(t, -5, 2, 1);

        run(t);
        assertThat(overdueEvents()).hasSize(1);
        LoanOverdueReminder first = overdueEvents().get(0);
        assertThat(first.reminderNo()).isEqualTo(1);
        assertThat(first.maxReminders()).isEqualTo(3);
        assertThat(first.daysOverdue()).isEqualTo(5);
        assertThat(first.itemCount()).isEqualTo(1);

        run(t); // cùng ngày: chưa đủ chu kỳ 3 ngày
        assertThat(overdueEvents()).hasSize(1);

        for (int expectedNo = 2; expectedNo <= 3; expectedNo++) {
            sql("UPDATE loan_reminders SET sent_on = sent_on - 3 WHERE borrowing_id = '" + p + "'"); // 3 ngày trôi qua
            events.clear();
            run(t);
            assertThat(overdueEvents()).hasSize(1);
            assertThat(overdueEvents().get(0).reminderNo()).isEqualTo(expectedNo);
        }

        sql("UPDATE loan_reminders SET sent_on = sent_on - 30 WHERE borrowing_id = '" + p + "'");
        events.clear();
        run(t); // đã đủ 3 lần: dừng dù đã quá chu kỳ
        assertThat(events).isEmpty();
        assertThat(count("borrowing_id = '" + p + "' AND kind = 'OVERDUE'")).isEqualTo(3);
    }

    @Test
    void anHanChiNhacKhiTreQuaSoNgayAnHan() throws Exception {
        UUID t = UUID.randomUUID();
        setPolicy(t, 2, 2, 3, 3);
        UUID tre2 = loan(t, -2, 1, 0); // trễ 2 ngày, ân hạn 2: chưa nhắc
        UUID tre3 = loan(t, -3, 1, 0);

        run(t);

        assertThat(overdueEvents()).extracting(e -> e.borrowingId()).containsExactly(tre3);
        assertThat(tre2).isNotNull();
    }

    @Test
    void chinhSachTatNhacHanThiKhongNhac() throws Exception {
        UUID t = UUID.randomUUID();
        setPolicy(t, 0, 0, 3, 0);
        loan(t, 1, 1, 0);
        loan(t, -5, 1, 0);

        SendLoanRemindersUseCase.Summary s = run(t);

        assertThat(events).isEmpty();
        assertThat(s.dueSoon()).isZero();
        assertThat(s.overdue()).isZero();
    }

    @Test
    void chuKyTheoChinhSachCuaTenant() throws Exception {
        UUID t = UUID.randomUUID();
        setPolicy(t, 0, 2, 1, 3); // chu kỳ 1 ngày
        UUID p = loan(t, -4, 1, 0);

        run(t);
        sql("UPDATE loan_reminders SET sent_on = sent_on - 1 WHERE borrowing_id = '" + p + "'");
        run(t);

        assertThat(overdueEvents()).extracting(LoanOverdueReminder::reminderNo).containsExactly(1, 2);
    }

    @Test
    void nhieuTenantChayDocLapVaKhongThayPhieuCuaNhau() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID pa = loan(a, 1, 1, 0);
        UUID pb = loan(b, 1, 1, 0);

        run(a);
        assertThat(dueSoonIds()).containsExactly(pa);
        assertThat(count("tenant_id = '" + b + "'")).isZero();

        events.clear();
        run(b);
        assertThat(dueSoonIds()).containsExactly(pb);
        assertThat(count("tenant_id = '" + a + "'")).isEqualTo(1);
        assertThat(count("tenant_id = '" + b + "'")).isEqualTo(1);
    }

    @Test
    void phatLoiThiRollbackLoKhongConDongLoanReminders() throws Exception {
        UUID t = UUID.randomUUID();
        loan(t, 1, 1, 0);
        loan(t, 2, 1, 0);
        doThrow(new IllegalStateException("Kafka lỗi")).when(publisher).publishAndWait(any());

        assertThatThrownBy(() -> run(t)).isInstanceOf(IllegalStateException.class);
        assertThat(count("tenant_id = '" + t + "'")).isZero();

        doNothing().when(publisher).publishAndWait(any());
        doAnswer(i -> events.add(i.getArgument(0))).when(publisher).publishAndWait(any());
        run(t); // mai chạy lại: nhắc đủ
        assertThat(count("tenant_id = '" + t + "'")).isEqualTo(2);
    }

    @Test
    void eventIdTatDinhGiuNguyenKhiRollbackRoiChayLai() throws Exception {
        UUID t = UUID.randomUUID();
        UUID p = loan(t, 1, 1, 0);
        run(t);
        UUID first = events.get(0).eventId();

        sql("DELETE FROM loan_reminders WHERE borrowing_id = '" + p + "'"); // mô phỏng commit hỏng sau khi đã phát
        events.clear();
        run(t);

        assertThat(events.get(0).eventId()).isEqualTo(first);
    }

    @Test
    void duyetNhieuLoVoiLoNho() throws Exception {
        UUID t = UUID.randomUUID();
        for (int i = 0; i < 7; i++) loan(t, 1, 1, 0);
        SendLoanRemindersUseCase small = new SendLoanRemindersUseCase(policies, batches, clock, 3);

        SendLoanRemindersUseCase.Summary s = TenantContext.callAs(t, () -> small.execute(t));

        assertThat(s.dueSoon()).isEqualTo(7);
        assertThat(dueSoonIds()).doesNotHaveDuplicates().hasSize(7);
        assertThat(count("tenant_id = '" + t + "'")).isEqualTo(7);
    }
}
