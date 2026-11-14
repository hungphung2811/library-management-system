package lms.identity.user.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.user.domain.InvalidReaderException;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderStatus;
import lms.identity.user.domain.ReaderType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReaderProfileTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    ReaderProfile issue(LocalDate expiry) {
        return ReaderProfile.issue(UUID.randomUUID(), "SV001", "Nguyễn Văn A", "a@x.vn", ReaderType.STUDENT, expiry, TODAY);
    }

    @Test
    void theConHanTinhDenHetNgayHan() {
        ReaderProfile p = issue(TODAY.plusDays(1));
        assertTrue(p.isCardValid(TODAY));
        assertTrue(p.isCardValid(TODAY.plusDays(1)));
        assertFalse(p.isCardValid(TODAY.plusDays(2)));
    }

    @Test
    void taiKhoanBiKhoaKhongHopLeDuConHan() {
        ReaderProfile p = issue(TODAY.plusYears(1));
        p.lock();
        assertEquals(ReaderStatus.LOCKED, p.status());
        assertFalse(p.isCardValid(TODAY));
        p.unlock();
        assertTrue(p.isCardValid(TODAY));
    }

    @Test
    void capTheYeuCauHanSauNgayCap() {
        assertThrows(InvalidReaderException.class, () -> issue(TODAY));
        assertThrows(InvalidReaderException.class, () -> issue(TODAY.minusDays(1)));
    }

    @Test
    void giaHanTheChiChapNhanHanMoiSauHanHienTai() {
        ReaderProfile p = issue(TODAY.plusMonths(6));
        assertThrows(InvalidReaderException.class, () -> p.renewCard(TODAY.plusMonths(3), TODAY));
        assertThrows(InvalidReaderException.class, () -> p.renewCard(TODAY.minusDays(1), TODAY));
        p.renewCard(TODAY.plusYears(1), TODAY);
        assertEquals(TODAY.plusYears(1), p.cardExpiryDate());
    }
}
