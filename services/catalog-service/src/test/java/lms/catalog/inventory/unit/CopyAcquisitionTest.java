package lms.catalog.inventory.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lms.catalog.inventory.domain.AcquisitionSource;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.ConditionGrade;
import lms.catalog.inventory.domain.CopyAcquisition;
import lms.catalog.inventory.domain.CopyStatus;
import lms.catalog.inventory.domain.InvalidCopyException;
import org.junit.jupiter.api.Test;

class CopyAcquisitionTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Test
    void macDinhLaRong() {
        assertTrue(CopyAcquisition.EMPTY.isEmpty());
        assertTrue(BookCopy.register(UUID.randomUUID(), "B1").acquisition().isEmpty());
    }

    @Test
    void hopLeVaChuanHoaGhiChu() {
        CopyAcquisition a = new CopyAcquisition(TODAY.minusDays(30), AcquisitionSource.PURCHASE, 50_000L,
                ConditionGrade.GOOD, "  Gáy hơi mòn  ", TODAY);
        assertEquals("Gáy hơi mòn", a.conditionNote());
        assertNull(new CopyAcquisition(null, null, null, null, "   ", null).conditionNote());
    }

    @Test
    void ngayKhongOTuongLai() {
        assertThrows(InvalidCopyException.class, () -> new CopyAcquisition(TODAY.plusDays(1), null, null, null, null, null));
        assertThrows(InvalidCopyException.class, () -> new CopyAcquisition(null, null, null, null, null, TODAY.plusDays(1)));
        assertEquals(TODAY, new CopyAcquisition(TODAY, null, null, null, null, TODAY).acquiredDate());
    }

    @Test
    void ngayKiemKeKhongTruocNgayNhap() {
        assertThrows(InvalidCopyException.class,
                () -> new CopyAcquisition(TODAY.minusDays(1), null, null, null, null, TODAY.minusDays(2)));
        // thiếu một trong hai ngày thì không so sánh
        assertEquals(TODAY.minusDays(5), new CopyAcquisition(null, null, null, null, null, TODAY.minusDays(5))
                .lastInventoryDate());
    }

    @Test
    void giaMuaChiHopLeKhiNguonLaPurchase() {
        assertEquals(0L, new CopyAcquisition(null, AcquisitionSource.PURCHASE, 0L, null, null, null).purchasePrice());
        assertThrows(InvalidCopyException.class,
                () -> new CopyAcquisition(null, AcquisitionSource.PURCHASE, -1L, null, null, null));
        assertThrows(InvalidCopyException.class,
                () -> new CopyAcquisition(null, AcquisitionSource.DONATION, 1000L, null, null, null));
        assertThrows(InvalidCopyException.class, () -> new CopyAcquisition(null, null, 1000L, null, null, null));
        assertNull(new CopyAcquisition(null, AcquisitionSource.DONATION, null, null, null, null).purchasePrice());
    }

    @Test
    void ghiChuToiDa300KyTu() {
        assertEquals(300, new CopyAcquisition(null, null, null, null, "a".repeat(300), null).conditionNote().length());
        assertThrows(InvalidCopyException.class,
                () -> new CopyAcquisition(null, null, null, null, "a".repeat(301), null));
    }

    @Test
    void banSaoGiuThongTinKhiDoiTrangThaiVaThayDuocToanBo() {
        CopyAcquisition a = new CopyAcquisition(TODAY.minusDays(10), AcquisitionSource.EXCHANGE, null,
                ConditionGrade.FAIR, "x", TODAY);
        BookCopy copy = BookCopy.register(UUID.randomUUID(), "B2", null, a);
        copy.changeStatus(CopyStatus.LOANED);
        assertEquals(a, copy.acquisition());
        copy.moveToShelfLocation(UUID.randomUUID());
        assertEquals(a, copy.acquisition());
        copy.updateAcquisition(null);
        assertTrue(copy.acquisition().isEmpty());
    }
}
