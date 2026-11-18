package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.borrowing.application.LoanReminderCandidate;
import lms.circulation.borrowing.application.LoanReminderPort;
import lms.circulation.borrowing.application.LoanReminderRecord;
import lms.circulation.borrowing.application.PublishLoanReminderEventPort;
import lms.circulation.borrowing.application.SendLoanReminderBatchUseCase;
import lms.circulation.borrowing.application.SendLoanRemindersUseCase;
import lms.circulation.borrowing.domain.LoanDueSoon;
import lms.circulation.borrowing.domain.LoanOverdueReminder;
import lms.circulation.borrowing.domain.LoanReminderIds;
import lms.circulation.borrowing.domain.LoanReminderKind;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.domain.CirculationPolicySnapshot;
import lms.common.kernel.DomainEvent;
import org.junit.jupiter.api.Test;

/**
 * Điều phối nhắc hạn (ADR-0030 D11) với cổng giả: tham số truy vấn theo chính sách và múi giờ, số thứ tự lần nhắc, eventId
 * tất định, payload không email, duyệt keyset theo lô, phát lỗi thì dừng (transaction thật rollback ở IT).
 * Quy tắc chọn phiếu (trần, chu kỳ, khử trùng, gia hạn) nằm ở SQL nên được kiểm bằng LoanReminderIT.
 */
class SendLoanRemindersUseCaseTest {

    static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");
    static final UUID TENANT_A = UUID.randomUUID();
    static final UUID TENANT_B = UUID.randomUUID();

    /** Cổng giả: ghi lại tham số, trả các trang đã chuẩn bị theo thứ tự gọi. */
    static class FakePort implements LoanReminderPort {
        final List<List<LoanReminderCandidate>> dueSoonPages = new ArrayList<>();
        final List<List<LoanReminderCandidate>> overduePages = new ArrayList<>();
        final List<Object[]> dueSoonCalls = new ArrayList<>();
        final List<Object[]> overdueCalls = new ArrayList<>();
        final List<LoanReminderRecord> saved = new ArrayList<>();

        @Override
        public List<LoanReminderCandidate> findDueSoon(LocalDate from, LocalDate to, UUID afterId, int limit) {
            dueSoonCalls.add(new Object[]{from, to, afterId, limit});
            return dueSoonPages.isEmpty() ? List.of() : dueSoonPages.remove(0);
        }

        @Override
        public List<LoanReminderCandidate> findOverdue(LocalDate dueBefore, int maxReminders, LocalDate lastSentOnOrBefore,
                                                       UUID afterId, int limit) {
            overdueCalls.add(new Object[]{dueBefore, maxReminders, lastSentOnOrBefore, afterId, limit});
            return overduePages.isEmpty() ? List.of() : overduePages.remove(0);
        }

        @Override
        public void saveAll(List<LoanReminderRecord> records) {
            saved.addAll(records);
        }
    }

    static class FakePublisher implements PublishLoanReminderEventPort {
        final List<DomainEvent> published = new ArrayList<>();
        int failAtCall = -1;

        @Override
        public void publishAndWait(DomainEvent event) {
            if (published.size() == failAtCall) {
                throw new IllegalStateException("Kafka lỗi");
            }
            published.add(event);
        }
    }

    static GetCirculationPolicyUseCase policyOf(CirculationPolicy policy) {
        return new GetCirculationPolicyUseCase(new CirculationPolicyRepository() {
            @Override
            public Optional<CirculationPolicySnapshot> find() {
                return Optional.of(new CirculationPolicySnapshot(policy, 1, Instant.EPOCH, null));
            }

            @Override
            public CirculationPolicySnapshot save(CirculationPolicy p, UUID by, Instant now) {
                throw new UnsupportedOperationException();
            }
        });
    }

    static Clock at(String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), HCM);
    }

    static LoanReminderCandidate cand(UUID id, LocalDate due, int items, int sent) {
        return new LoanReminderCandidate(id, UUID.randomUUID(), due, items, sent);
    }

    static SendLoanRemindersUseCase useCase(FakePort port, FakePublisher pub, CirculationPolicy policy, Clock clock,
                                           int batchSize) {
        return new SendLoanRemindersUseCase(policyOf(policy), new SendLoanReminderBatchUseCase(port, pub, clock), clock,
                batchSize);
    }

    @Test
    void macDinhTruyVanSapHanTrongKhoangHomNayDenHomNayCongSoNgay() {
        FakePort port = new FakePort();
        // 2026-11-19 03:00 giờ Việt Nam
        useCase(port, new FakePublisher(), CirculationPolicy.defaults(), at("2026-11-18T20:00:00Z"), 500)
                .execute(TENANT_A);

        Object[] call = port.dueSoonCalls.get(0);
        assertThat(call[0]).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(call[1]).isEqualTo(LocalDate.of(2026, 11, 21));
        // quá hạn: chưa ân hạn nên hạn trả < hôm nay; chu kỳ 3 ngày; trần 3
        Object[] over = port.overdueCalls.get(0);
        assertThat(over[0]).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(over[1]).isEqualTo(3);
        assertThat(over[2]).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void ngayTinhTheoMuiGioVietNamKhongTheoUtc() {
        FakePort port = new FakePort();
        // 18:00 UTC ngày 18 = 01:00 ngày 19 giờ Việt Nam; đồng hồ UTC sẽ ra ngày 18
        Clock vn = Clock.fixed(Instant.parse("2026-11-18T18:00:00Z"), HCM);
        useCase(port, new FakePublisher(), CirculationPolicy.defaults(), vn, 500).execute(TENANT_A);
        assertThat(port.dueSoonCalls.get(0)[0]).isEqualTo(LocalDate.of(2026, 11, 19));

        FakePort utcPort = new FakePort();
        Clock utc = Clock.fixed(Instant.parse("2026-11-18T18:00:00Z"), ZoneOffset.UTC);
        useCase(utcPort, new FakePublisher(), CirculationPolicy.defaults(), utc, 500).execute(TENANT_A);
        assertThat(utcPort.dueSoonCalls.get(0)[0]).isEqualTo(LocalDate.of(2026, 11, 18));
    }

    @Test
    void anHanTruVaoMocQuaHan() {
        FakePort port = new FakePort();
        CirculationPolicy p = new CirculationPolicy(14, 2, 14, 5, 5000, 2, 3, 2, 3, 3);
        useCase(port, new FakePublisher(), p, at("2026-11-19T00:00:00Z"), 500).execute(TENANT_A);
        // hôm nay 19/11 (07:00 VN), ân hạn 2 ngày: chỉ phiếu hạn trước 17/11 (đã trễ 3 ngày) mới nhắc
        assertThat(port.overdueCalls.get(0)[0]).isEqualTo(LocalDate.of(2026, 11, 17));
    }

    @Test
    void sapHanPhatSuKienKhongChuaEmailVaGhiSo() {
        FakePort port = new FakePort();
        UUID borrowing = UUID.randomUUID();
        LocalDate due = LocalDate.of(2026, 11, 21);
        port.dueSoonPages.add(List.of(cand(borrowing, due, 3, 0)));
        FakePublisher pub = new FakePublisher();

        SendLoanRemindersUseCase.Summary s = useCase(port, pub, CirculationPolicy.defaults(),
                at("2026-11-19T00:00:00Z"), 500).execute(TENANT_A);

        assertThat(s.dueSoon()).isEqualTo(1);
        assertThat(pub.published).hasSize(1);
        LoanDueSoon e = (LoanDueSoon) pub.published.get(0);
        assertThat(e.borrowingId()).isEqualTo(borrowing);
        assertThat(e.dueDate()).isEqualTo(due);
        assertThat(e.daysBefore()).isEqualTo(2);
        assertThat(e.itemCount()).isEqualTo(3);
        assertThat(e.aggregateId()).isEqualTo(borrowing.toString());
        assertThat(e.eventType()).isEqualTo("LoanDueSoon");
        assertThat(e.version()).isEqualTo(1);
        LoanReminderRecord r = port.saved.get(0);
        assertThat(r.kind()).isEqualTo(LoanReminderKind.DUE_SOON);
        assertThat(r.seq()).isEqualTo(1);
        assertThat(r.sentOn()).isEqualTo(LocalDate.of(2026, 11, 19));
        assertThat(r.eventId()).isEqualTo(e.eventId());
    }

    @Test
    void hanLaHomNayVanNhacVoiDaysBeforeBang0() {
        FakePort port = new FakePort();
        port.dueSoonPages.add(List.of(cand(UUID.randomUUID(), LocalDate.of(2026, 11, 19), 1, 0)));
        FakePublisher pub = new FakePublisher();
        useCase(port, pub, CirculationPolicy.defaults(), at("2026-11-19T00:00:00Z"), 500).execute(TENANT_A);
        assertThat(((LoanDueSoon) pub.published.get(0)).daysBefore()).isZero();
    }

    @Test
    void quaHanSoLanNhacLaSoDaNhacCong1VaTinhSoNgayTre() {
        FakePort port = new FakePort();
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        port.overduePages.add(List.of(cand(b1, LocalDate.of(2026, 11, 10), 1, 0),
                cand(b2, LocalDate.of(2026, 11, 10), 2, 2)));
        FakePublisher pub = new FakePublisher();

        SendLoanRemindersUseCase.Summary s = useCase(port, pub, CirculationPolicy.defaults(),
                at("2026-11-19T00:00:00Z"), 500).execute(TENANT_A);

        assertThat(s.overdue()).isEqualTo(2);
        LoanOverdueReminder first = (LoanOverdueReminder) pub.published.get(0);
        assertThat(first.reminderNo()).isEqualTo(1);
        assertThat(first.maxReminders()).isEqualTo(3);
        assertThat(first.daysOverdue()).isEqualTo(9);
        assertThat(first.itemCount()).isEqualTo(1);
        assertThat(first.eventType()).isEqualTo("LoanOverdueReminder");
        LoanOverdueReminder third = (LoanOverdueReminder) pub.published.get(1);
        assertThat(third.reminderNo()).isEqualTo(3);
        assertThat(port.saved).extracting(LoanReminderRecord::seq).containsExactly(1, 3);
        assertThat(port.saved).extracting(LoanReminderRecord::kind).containsOnly(LoanReminderKind.OVERDUE);
    }

    @Test
    void eventIdTatDinhGiaoLaiHoacChayLaiKhongDoi() {
        UUID b = UUID.randomUUID();
        LocalDate due = LocalDate.of(2026, 11, 21);
        List<UUID> ids = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            FakePort port = new FakePort();
            port.dueSoonPages.add(List.of(cand(b, due, 1, 0)));
            FakePublisher pub = new FakePublisher();
            useCase(port, pub, CirculationPolicy.defaults(), at("2026-11-19T00:00:00Z"), 500).execute(TENANT_A);
            ids.add(pub.published.get(0).eventId());
        }
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(ids.get(0)).isEqualTo(LoanReminderIds.eventId(TENANT_A, b, LoanReminderKind.DUE_SOON, due, 1));
    }

    @Test
    void eventIdKhacNhauTheoTenantLoaiHanVaLanNhac() {
        UUID b = UUID.randomUUID();
        LocalDate due = LocalDate.of(2026, 11, 10);
        UUID base = LoanReminderIds.eventId(TENANT_A, b, LoanReminderKind.OVERDUE, due, 1);
        assertThat(LoanReminderIds.eventId(TENANT_B, b, LoanReminderKind.OVERDUE, due, 1)).isNotEqualTo(base);
        assertThat(LoanReminderIds.eventId(TENANT_A, b, LoanReminderKind.DUE_SOON, due, 1)).isNotEqualTo(base);
        assertThat(LoanReminderIds.eventId(TENANT_A, b, LoanReminderKind.OVERDUE, due.plusDays(14), 1)).isNotEqualTo(base);
        assertThat(LoanReminderIds.eventId(TENANT_A, b, LoanReminderKind.OVERDUE, due, 2)).isNotEqualTo(base);
    }

    @Test
    void tatNhacTruocHanKhiSoNgayBang0VaTatQuaHanKhiTranBang0() {
        FakePort port = new FakePort();
        CirculationPolicy off = new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 0, 3, 0);
        SendLoanRemindersUseCase.Summary s = useCase(port, new FakePublisher(), off, at("2026-11-19T00:00:00Z"), 500)
                .execute(TENANT_A);
        assertThat(port.dueSoonCalls).isEmpty();
        assertThat(port.overdueCalls).isEmpty();
        assertThat(s).isEqualTo(new SendLoanRemindersUseCase.Summary(0, 0));
    }

    @Test
    void duyetNhieuLoKeysetTheoIdPhieuCuoiCuaLoTruoc() {
        FakePort port = new FakePort();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        LocalDate due = LocalDate.of(2026, 11, 20);
        port.dueSoonPages.add(List.of(cand(a, due, 1, 0), cand(b, due, 1, 0))); // đầy lô (2) nên còn lô sau
        port.dueSoonPages.add(List.of(cand(c, due, 1, 0)));                     // lô cuối chưa đầy
        FakePublisher pub = new FakePublisher();

        SendLoanRemindersUseCase.Summary s = useCase(port, pub, CirculationPolicy.defaults(),
                at("2026-11-19T00:00:00Z"), 2).execute(TENANT_A);

        assertThat(s.dueSoon()).isEqualTo(3);
        assertThat(port.dueSoonCalls).hasSize(2);
        assertThat(port.dueSoonCalls.get(0)[2]).isEqualTo(new UUID(0L, 0L));
        assertThat(port.dueSoonCalls.get(1)[2]).isEqualTo(b);
        assertThat(port.dueSoonCalls.get(1)[3]).isEqualTo(2);
    }

    @Test
    void loPhatLoiNemRaVaKhongNuotLoi() {
        FakePort port = new FakePort();
        port.dueSoonPages.add(List.of(cand(UUID.randomUUID(), LocalDate.of(2026, 11, 20), 1, 0),
                cand(UUID.randomUUID(), LocalDate.of(2026, 11, 20), 1, 0)));
        FakePublisher pub = new FakePublisher();
        pub.failAtCall = 1;

        assertThatThrownBy(() -> useCase(port, pub, CirculationPolicy.defaults(), at("2026-11-19T00:00:00Z"), 500)
                .execute(TENANT_A)).isInstanceOf(IllegalStateException.class);
        // lỗi nổi lên để @Transactional rollback cả lô (kể cả dòng loan_reminders đã saveAll)
        assertThat(pub.published).hasSize(1);
    }

    @Test
    void khongPhieuNaoThiKhongGhiKhongPhat() {
        FakePort port = new FakePort();
        FakePublisher pub = new FakePublisher();
        useCase(port, pub, CirculationPolicy.defaults(), at("2026-11-19T00:00:00Z"), 500).execute(TENANT_A);
        assertThat(port.saved).isEmpty();
        assertThat(pub.published).isEmpty();
    }

    @Test
    void sukienKhongCoTruongEmailHayTen() {
        for (Class<?> type : List.of(LoanDueSoon.class, LoanOverdueReminder.class)) {
            List<String> names = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
            assertThat(names).as(type.getSimpleName()).noneMatch(n -> n.toLowerCase().contains("mail")
                    || n.toLowerCase().contains("name") || n.toLowerCase().contains("phone"));
        }
        assertThat(Arrays.stream(LoanDueSoon.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("eventId", "borrowingId", "readerId", "dueDate", "daysBefore", "itemCount", "occurredAt");
        assertThat(Arrays.stream(LoanOverdueReminder.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("eventId", "borrowingId", "readerId", "dueDate", "daysOverdue", "reminderNo",
                        "maxReminders", "itemCount", "occurredAt");
    }
}
