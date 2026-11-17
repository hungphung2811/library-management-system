package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.circulation.borrowing.application.GetBorrowingUseCase;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetBorrowingUseCaseTest {

    final BorrowingRepository repository = mock(BorrowingRepository.class);
    final GetBorrowingUseCase useCase = new GetBorrowingUseCase(repository);
    final UUID owner = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    final BorrowingRecord record = BorrowingRecord.borrow(owner, List.of(UUID.randomUUID()), LocalDate.of(2026, 11, 16));

    @Test
    void chuPhieuXemDuocVaDieuKienSoHuuNamTrongTruyVan() {
        when(repository.findByIdAndUserId(record.id(), owner)).thenReturn(Optional.of(record));

        assertThat(useCase.executeOwn(record.id().value(), owner).id()).isEqualTo(record.id().value());
        verify(repository, never()).findById(any());
    }

    @Test
    void nguoiKhacNhan404VaKhongDocPhieuRoiMoiSo() {
        when(repository.findByIdAndUserId(record.id(), other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.executeOwn(record.id().value(), other))
                .isInstanceOf(BorrowingNotFoundException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void khongCoUserIdThi404() {
        assertThatThrownBy(() -> useCase.executeOwn(record.id().value(), null))
                .isInstanceOf(BorrowingNotFoundException.class);
        verify(repository, never()).findByIdAndUserId(any(), any());
    }

    @Test
    void nhanVienCoKhoaXemPhieuBatKy() {
        when(repository.findById(record.id())).thenReturn(Optional.of(record));

        assertThat(useCase.executeAny(record.id().value()).userId()).isEqualTo(owner);
    }

    @Test
    void phieuBatKyKhongTonTaiLa404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(new BorrowingId(id))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.executeAny(id)).isInstanceOf(BorrowingNotFoundException.class);
    }
}
