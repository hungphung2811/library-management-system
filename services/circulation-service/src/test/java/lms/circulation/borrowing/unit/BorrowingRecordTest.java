package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.circulation.borrowing.domain.BookBorrowedEvent;
import lms.circulation.borrowing.domain.BookReturnedEvent;
import lms.circulation.borrowing.domain.InvalidBorrowingException;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BorrowingRecordTest {

    private final LocalDate today = LocalDate.of(2026, 1, 1);
    private final UUID userId = UUID.randomUUID();
    private final UUID copy1 = UUID.randomUUID();
    private final UUID copy2 = UUID.randomUUID();

    @Test
    void muonSachDatHanTra14NgayVaPhatSinhEvent() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today);

        assertThat(record.dueDate()).isEqualTo(today.plusDays(14));
        assertThat(record.status()).isEqualTo(BorrowingStatus.ACTIVE);
        assertThat(record.pullEvents()).hasSize(1).first().isInstanceOf(BookBorrowedEvent.class);
        assertThat(record.pullEvents()).isEmpty();
    }

    @Test
    void khongDuocMuonVuotGioiHanMotPhieu() {
        List<UUID> copies = IntStream.range(0, 6).mapToObj(i -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> BorrowingRecord.borrow(userId, copies, today))
                .isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void khongDuocMuonBanSaoTrung() {
        assertThatThrownBy(() -> BorrowingRecord.borrow(userId, List.of(copy1, copy1), today))
                .isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void traHetBanSaoThiPhieuChuyenSangReturned() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today);
        record.pullEvents();

        record.returnCopy(copy1, today.plusDays(3));
        assertThat(record.status()).isEqualTo(BorrowingStatus.ACTIVE);
        record.returnCopy(copy2, today.plusDays(3));
        assertThat(record.status()).isEqualTo(BorrowingStatus.RETURNED);
        assertThat(record.pullEvents()).hasSize(2).allMatch(e -> e instanceof BookReturnedEvent);
    }

    @Test
    void traTreTinhSoNgayQuaHan() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1), today);
        record.pullEvents();

        record.returnCopy(copy1, today.plusDays(14 + 5));

        BookReturnedEvent event = (BookReturnedEvent) record.pullEvents().get(0);
        assertThat(event.overdueDays()).isEqualTo(5);
    }

    @Test
    void khongTraDuocHaiLanCungMotBanSao() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), today);
        record.returnCopy(copy1, today);

        assertThatThrownBy(() -> record.returnCopy(copy1, today)).isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void giaHanToiDaHaiLan() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1), today);

        record.renew(today.plusDays(1));
        record.renew(today.plusDays(2));

        assertThat(record.dueDate()).isEqualTo(today.plusDays(14 + 28));
        assertThatThrownBy(() -> record.renew(today.plusDays(3))).isInstanceOf(InvalidBorrowingException.class);
    }

    @Test
    void khongGiaHanKhiDaQuaHan() {
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1), today);

        assertThatThrownBy(() -> record.renew(today.plusDays(15))).isInstanceOf(InvalidBorrowingException.class);
    }
}
