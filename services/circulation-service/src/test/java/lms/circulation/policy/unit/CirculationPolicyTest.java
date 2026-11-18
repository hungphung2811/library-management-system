package lms.circulation.policy.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.UpdateCirculationPolicyRequest;
import lms.circulation.policy.application.UpdateCirculationPolicyUseCase;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.domain.CirculationPolicySnapshot;
import lms.circulation.policy.domain.InvalidCirculationPolicyException;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import org.junit.jupiter.api.Test;

class CirculationPolicyTest {

    /** Kho trong bộ nhớ, mô phỏng version tăng mỗi lần lưu. */
    static class InMemoryRepo implements CirculationPolicyRepository {
        CirculationPolicySnapshot stored;

        @Override
        public Optional<CirculationPolicySnapshot> find() {
            return Optional.ofNullable(stored);
        }

        @Override
        public CirculationPolicySnapshot save(CirculationPolicy policy, UUID by, Instant now) {
            stored = new CirculationPolicySnapshot(policy, stored == null ? 1 : stored.version() + 1, now, by);
            return stored;
        }
    }

    final InMemoryRepo repo = new InMemoryRepo();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
    final GetCirculationPolicyUseCase get = new GetCirculationPolicyUseCase(repo);
    final UpdateCirculationPolicyUseCase update = new UpdateCirculationPolicyUseCase(repo, clock);

    static UpdateCirculationPolicyRequest request(Long version, int loanDays) {
        return new UpdateCirculationPolicyRequest(loanDays, 1, 7, 8, 2000L, 1, 5, version);
    }

    @Test
    void macDinhTrungHanhViCu() {
        CirculationPolicy d = CirculationPolicy.defaults();
        assertEquals(14, d.loanDays());
        assertEquals(2, d.maxRenewals());
        assertEquals(5000, d.finePerDay());
        assertEquals(3, d.reservationHoldDays());
        assertEquals(0, d.graceDays());
    }

    @Test
    void chuaCauHinhTraMacDinhVaKhongCustom() {
        CirculationPolicyResponse r = get.execute();
        assertFalse(r.custom());
        assertEquals(0, r.version());
        assertEquals(14, r.loanDays());
    }

    @Test
    void luuLanDauRoiSuaDungVersion() {
        CirculationPolicyResponse first = update.execute(request(null, 21), UUID.randomUUID());
        assertTrue(first.custom());
        assertEquals(21, first.loanDays());
        CirculationPolicyResponse second = update.execute(request(first.version(), 30), UUID.randomUUID());
        assertEquals(30, second.loanDays());
        assertEquals(first.version() + 1, second.version());
    }

    @Test
    void saiVersionBiTuChoi() {
        update.execute(request(0L, 21), UUID.randomUUID());
        assertThrows(PolicyVersionConflictException.class, () -> update.execute(request(0L, 30), UUID.randomUUID()));
    }

    @Test
    void giaTriNgoaiKhoangBiTuChoiBangTiengViet() {
        InvalidCirculationPolicyException e = assertThrows(InvalidCirculationPolicyException.class,
                () -> new CirculationPolicy(0, 2, 14, 5, 5000, 0, 3));
        assertTrue(e.getMessage().contains("Số ngày mượn"));
        assertThrows(InvalidCirculationPolicyException.class, () -> new CirculationPolicy(14, 2, 14, 5, -1, 0, 3));
        assertThrows(InvalidCirculationPolicyException.class, () -> new CirculationPolicy(14, 2, 14, 5, 5000, 0, 31));
    }

    // ---- cấu hình nhắc hạn (ADR-0030 D11) ----

    @Test
    void macDinhNhacHanLa2Ngay3Ngay3Lan() {
        CirculationPolicy d = CirculationPolicy.defaults();
        assertEquals(2, d.dueReminderDaysBefore());
        assertEquals(3, d.overdueReminderIntervalDays());
        assertEquals(3, d.overdueReminderMax());
        CirculationPolicyResponse r = get.execute();
        assertEquals(2, r.dueReminderDaysBefore());
        assertEquals(3, r.overdueReminderIntervalDays());
        assertEquals(3, r.overdueReminderMax());
    }

    @Test
    void dangBayTruongVanDungDuocVaLayMacDinhNhacHan() {
        CirculationPolicy p = new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3);
        assertEquals(2, p.dueReminderDaysBefore());
        assertEquals(3, p.overdueReminderIntervalDays());
        assertEquals(3, p.overdueReminderMax());
    }

    @Test
    void khoangNhacHanHopLeVaBiTuChoiNgoaiKhoang() {
        new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 0, 1, 0);
        new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 30, 30, 20);
        InvalidCirculationPolicyException e = assertThrows(InvalidCirculationPolicyException.class,
                () -> new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 31, 3, 3));
        assertTrue(e.getMessage().contains("nhắc trước hạn"));
        assertThrows(InvalidCirculationPolicyException.class, () -> new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, -1, 3, 3));
        e = assertThrows(InvalidCirculationPolicyException.class,
                () -> new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 2, 0, 3));
        assertTrue(e.getMessage().contains("Chu kỳ"));
        assertThrows(InvalidCirculationPolicyException.class, () -> new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 2, 31, 3));
        assertThrows(InvalidCirculationPolicyException.class, () -> new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 2, 3, 21));
        assertThrows(InvalidCirculationPolicyException.class, () -> new CirculationPolicy(14, 2, 14, 5, 5000, 0, 3, 2, 3, -1));
    }

    @Test
    void luuVaDocLaiBaTruongNhacHan() {
        UpdateCirculationPolicyRequest req = new UpdateCirculationPolicyRequest(14, 2, 14, 5, 5000L, 0, 3, null, 5, 7, 4);
        CirculationPolicyResponse saved = update.execute(req, UUID.randomUUID());
        assertEquals(5, saved.dueReminderDaysBefore());
        assertEquals(7, saved.overdueReminderIntervalDays());
        assertEquals(4, saved.overdueReminderMax());
        assertEquals(5, get.execute().dueReminderDaysBefore());
    }

    @Test
    void requestCuThieuBaTruongGiuGiaTriDangLuu() {
        update.execute(new UpdateCirculationPolicyRequest(14, 2, 14, 5, 5000L, 0, 3, null, 5, 7, 4), UUID.randomUUID());
        CirculationPolicyResponse after = update.execute(request(1L, 21), UUID.randomUUID());
        assertEquals(21, after.loanDays());
        assertEquals(5, after.dueReminderDaysBefore());
        assertEquals(7, after.overdueReminderIntervalDays());
        assertEquals(4, after.overdueReminderMax());
    }

    @Test
    void requestCuChuaCoCauHinhThiNhacHanLaMacDinh() {
        CirculationPolicyResponse first = update.execute(request(null, 21), UUID.randomUUID());
        assertEquals(2, first.dueReminderDaysBefore());
        assertEquals(3, first.overdueReminderIntervalDays());
        assertEquals(3, first.overdueReminderMax());
    }

    @Test
    void nhacHanNgoaiKhoangQuaUpdateBiTuChoi() {
        assertThrows(InvalidCirculationPolicyException.class, () -> update.execute(
                new UpdateCirculationPolicyRequest(14, 2, 14, 5, 5000L, 0, 3, null, 99, 3, 3), UUID.randomUUID()));
    }
}
