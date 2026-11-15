package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.circulation.borrowing.domain.BookBorrowedEvent;
import lms.circulation.borrowing.domain.BookLostEvent;
import lms.circulation.borrowing.domain.BookReturnedEvent;
import lms.circulation.borrowing.domain.BorrowingItem;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingStatus;
import lms.circulation.borrowing.domain.InvalidBorrowingException;
import lms.circulation.borrowing.domain.ItemCondition;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BorrowingRecordTest {

    private final LocalDate today = LocalDate.of(2026, 1, 1);
    private final UUID userId = UUID.randomUUID();
    private final UUID copy1 = UUID.randomUUID();
    private final UUID copy2 = UUID.randomUUID();

    @Test
    void hanTraTheoSoNgayCuaQuyDinhVaPhatSinhEvent() {
        BorrowingRecord student = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today, 14);
        BorrowingRecord lecturer = BorrowingRecord.borrow(userId, List.of(copy1), today, 30);

        assertThat(student.dueDate()).isEqualTo(today.plusDays(14));
        assertThat(lecturer.dueDate()).isEqualTo(today.plusDays(30));
        assertThat(student.status()).isEqualTo(BorrowingStatus.ACTIVE);
        assertThat(student.pullEvents()).hasSize(1).first().isInstanceOf(BookBorrowedEvent.class);
        assertThat(student.pullEvents()).isEmpty();
    }

    @Test
    void khongDuocMuonBanSaoTrung() {
        assertThatThrownBy(() -> BorrowingRecord.borrow(userId, List.of(copy1, copy1), today, 14))
                .isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void phieuPhaiCoItNhatMotBanSao() {
        assertThatThrownBy(() -> BorrowingRecord.borrow(userId, List.of(), today, 14))
                .isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void traHetBanSaoThiPhieuChuyenSangReturned() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today, 14);
        record.pullEvents();

        record.returnCopy(copy1, ItemCondition.GOOD, today.plusDays(3));
        assertThat(record.status()).isEqualTo(BorrowingStatus.ACTIVE);
        record.returnCopy(copy2, ItemCondition.DAMAGED, today.plusDays(3));
        assertThat(record.status()).isEqualTo(BorrowingStatus.RETURNED);
        assertThat(record.pullEvents()).hasSize(2).allMatch(e -> e instanceof BookReturnedEvent);
        assertThat(record.items()).extracting(i -> i.condition())
                .containsExactly(ItemCondition.GOOD, ItemCondition.DAMAGED);
    }

    @Test
    void traBanSaoGhiTinhTrangNguyenVenConDangMuonThiChuaCoTinhTrang() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today, 14);

        record.returnCopy(copy1, ItemCondition.GOOD, today.plusDays(1));

        var items = record.items();
        assertThat(items).filteredOn(i -> i.bookCopyId().equals(copy1))
                .singleElement().satisfies(i -> assertThat(i.condition()).isEqualTo(ItemCondition.GOOD));
        assertThat(items).filteredOn(i -> i.bookCopyId().equals(copy2))
                .singleElement().satisfies(i -> assertThat(i.condition()).isNull());
    }

    @Test
    void ngayTraVaTinhTrangPhaiCungCoHoacCungKhong() {
        assertThatThrownBy(() -> new BorrowingItem(copy1, today, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BorrowingItem(copy1, null, ItemCondition.LOST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void traTreTinhSoNgayQuaHan() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1), today, 14);
        record.pullEvents();

        record.returnCopy(copy1, ItemCondition.GOOD, today.plusDays(14 + 5));

        BookReturnedEvent event = (BookReturnedEvent) record.pullEvents().get(0);
        assertThat(event.overdueDays()).isEqualTo(5);
        assertThat(event.condition()).isEqualTo("GOOD");
    }

    @Test
    void khongTraDuocHaiLanCungMotBanSao() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today, 14);
        record.returnCopy(copy1, ItemCondition.GOOD, today);

        assertThatThrownBy(() -> record.returnCopy(copy1, ItemCondition.GOOD, today))
                .isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void khiTraKhongDuocDungTinhTrangLost() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1), today, 14);

        assertThatThrownBy(() -> record.returnCopy(copy1, ItemCondition.LOST, today))
                .isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void baoMatDongLuotMuonVoiTinhTrangLost() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today, 14);
        record.pullEvents();

        record.reportLost(copy1, today.plusDays(20));

        assertThat(record.status()).isEqualTo(BorrowingStatus.ACTIVE);
        assertThat(record.items().get(0).condition()).isEqualTo(ItemCondition.LOST);
        assertThat(record.pullEvents()).hasSize(1).first().isInstanceOf(BookLostEvent.class);
        assertThatThrownBy(() -> record.reportLost(copy1, today.plusDays(21))).isInstanceOf(InvalidBorrowingException.class);
        record.returnCopy(copy2, ItemCondition.GOOD, today.plusDays(21));
        assertThat(record.status()).isEqualTo(BorrowingStatus.RETURNED);
    }

    @Test
    void giaHanCongThemSoNgayMuonCuaPhieuToiDaHaiLan() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1), today, 30);

        record.renew(today.plusDays(1));
        record.renew(today.plusDays(2));

        assertThat(record.dueDate()).isEqualTo(today.plusDays(30 + 60));
        assertThatThrownBy(() -> record.renew(today.plusDays(3))).isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void khongGiaHanKhiDaQuaHan() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1), today, 14);

        assertThatThrownBy(() -> record.renew(today.plusDays(15))).isInstanceOf(InvalidBorrowingException.class);
    }
}
