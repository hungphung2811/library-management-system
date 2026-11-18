package lms.catalog.inventory.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.catalog.inventory.application.CopyDraft;
import lms.catalog.inventory.application.CopyWriteConflictException;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase.CreatedCopy;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase.NewCopy;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyBatchConflictException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Nhập bản sao theo lô (ADR-0024): kiểm bằng domain, một truy vấn mã vạch, một lần insert, sự kiện sau commit. */
class RegisterCopiesInBatchUseCaseTest {

    private final BookCopyRepository repository = mock(BookCopyRepository.class);
    private final List<Object> events = new ArrayList<>();
    private final RegisterCopiesInBatchUseCase useCase = new RegisterCopiesInBatchUseCase(repository, events::add, Clock.systemUTC());

    private static CopyDraft draft(String barcode) {
        return new CopyDraft(barcode, null, null, null, null, null, null, null);
    }

    @Test
    void checkDungLuatDomain() {
        assertThat(useCase.check(draft("OK-1"))).isNull();
        assertThat(useCase.check(new CopyDraft("X", null, LocalDate.now().plusDays(3), null, null, null, null, null)))
                .contains("tương lai");
        assertThat(useCase.check(new CopyDraft("X", null, null, null, 5L, null, null, null))).contains("PURCHASE");
        assertThat(useCase.check(new CopyDraft("X", null, null, "LẠ", null, null, null, null))).contains("Nguồn nhập");
        assertThat(useCase.check(draft("x".repeat(65)))).contains("64");
        assertThat(useCase.check(new CopyDraft("X", null, null, "purchase", 100L, "good", null, null))).isNull();
    }

    @Test
    void registerAllChiMotTruyVanMaVachVaMotLanInsertVaBoQuaTrung() {
        when(repository.findExistingBarcodes(any())).thenReturn(Set.of("B2"));
        UUID title = UUID.randomUUID();

        var result = useCase.registerAll(List.of(new NewCopy(title, draft("B1")), new NewCopy(title, draft("B2")),
                new NewCopy(title, draft(" B3 ")), new NewCopy(title, draft("B1"))));

        assertThat(result.skippedDuplicateIndexes()).containsExactlyInAnyOrder(1, 3);
        assertThat(result.created()).hasSize(2).allMatch(c -> c.titleId().equals(title));
        verify(repository).findExistingBarcodes(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<BookCopy>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).insertAll(captor.capture());
        assertThat(captor.getValue()).extracting(BookCopy::barcode).containsExactly("B1", "B3");
        assertThat(events).isEmpty(); // sự kiện chỉ phát sau commit, qua publishAvailable
    }

    @Test
    void xungDotGhiDongThoiThanhCopyWriteConflict() {
        doThrow(new CopyBatchConflictException(new RuntimeException("dup"))).when(repository).insertAll(any());

        assertThatThrownBy(() -> useCase.registerAll(List.of(new NewCopy(UUID.randomUUID(), draft("C1")))))
                .isInstanceOf(CopyWriteConflictException.class);
    }

    @Test
    void khongCoBanSaoThiKhongInsertLoRong() {
        var result = useCase.registerAll(List.of());
        assertThat(result.created()).isEmpty();
        verify(repository, never()).findExistingBarcodes(any());
    }

    @Test
    void publishAvailablePhatTungSuKien() {
        List<CreatedCopy> created = List.of(new CreatedCopy(UUID.randomUUID(), UUID.randomUUID()),
                new CreatedCopy(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(useCase.publishAvailable(created)).isEqualTo(2);
        assertThat(events).hasSize(2);
    }

    @Test
    void kafkaChamThiBoPhanConLaiCuaLo() {
        RegisterCopiesInBatchUseCase slow = new RegisterCopiesInBatchUseCase(repository, e -> {
            events.add(e);
            try {
                Thread.sleep(1100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, Clock.systemUTC());
        List<CreatedCopy> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) created.add(new CreatedCopy(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(slow.publishAvailable(created)).isEqualTo(1);
        assertThat(events).hasSize(1);
    }
}
