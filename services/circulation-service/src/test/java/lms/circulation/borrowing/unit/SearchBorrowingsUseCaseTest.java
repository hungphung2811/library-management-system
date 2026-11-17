package lms.circulation.borrowing.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.circulation.borrowing.application.BorrowingPageResponse;
import lms.circulation.borrowing.application.SearchBorrowingsQuery;
import lms.circulation.borrowing.application.SearchBorrowingsUseCase;
import lms.circulation.borrowing.domain.BorrowingId;
import lms.circulation.borrowing.domain.BorrowingItem;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.BorrowingSearchCriteria;
import lms.circulation.borrowing.domain.BorrowingSearchResult;
import lms.circulation.borrowing.domain.BorrowingStatus;
import lms.circulation.borrowing.domain.InvalidBorrowingQueryException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SearchBorrowingsUseCaseTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-16T03:00:00Z"), ZoneOffset.UTC);
    final BorrowingRepository repository = mock(BorrowingRepository.class);
    final SearchBorrowingsUseCase useCase = new SearchBorrowingsUseCase(repository, clock);

    private BorrowingSearchCriteria searchedWith(SearchBorrowingsQuery query) {
        when(repository.search(any())).thenReturn(new BorrowingSearchResult(List.of(), 0));
        useCase.execute(query);
        ArgumentCaptor<BorrowingSearchCriteria> captor = ArgumentCaptor.forClass(BorrowingSearchCriteria.class);
        verify(repository).search(captor.capture());
        return captor.getValue();
    }

    @Test
    void khongThamSoDungMacDinhTrang0Size20KhongLoc() {
        BorrowingSearchCriteria c = searchedWith(new SearchBorrowingsQuery(null, null, null, null, null));

        assertThat(c.status()).isNull();
        assertThat(c.readerId()).isNull();
        assertThat(c.overdueBefore()).isNull();
        assertThat(c.page()).isZero();
        assertThat(c.size()).isEqualTo(20);
    }

    @Test
    void locTheoStatusVaReaderVaPhanTrang() {
        UUID reader = UUID.randomUUID();

        BorrowingSearchCriteria c = searchedWith(new SearchBorrowingsQuery("returned", false, reader, 3, 50));

        assertThat(c.status()).isEqualTo(BorrowingStatus.RETURNED);
        assertThat(c.readerId()).isEqualTo(reader);
        assertThat(c.overdueBefore()).isNull();
        assertThat(c.page()).isEqualTo(3);
        assertThat(c.size()).isEqualTo(50);
    }

    @Test
    void overdueTrueLaActiveVaHanTruocHomNayTheoClock() {
        BorrowingSearchCriteria c = searchedWith(new SearchBorrowingsQuery(null, true, null, null, null));

        assertThat(c.status()).isEqualTo(BorrowingStatus.ACTIVE);
        assertThat(c.overdueBefore()).isEqualTo(TODAY);
    }

    @Test
    void overdueKetHopActiveVanLaActive() {
        BorrowingSearchCriteria c = searchedWith(new SearchBorrowingsQuery("ACTIVE", true, null, null, null));

        assertThat(c.status()).isEqualTo(BorrowingStatus.ACTIVE);
        assertThat(c.overdueBefore()).isEqualTo(TODAY);
    }

    @Test
    void overdueKetHopReturnedRongVaKhongTruyVanDb() {
        BorrowingPageResponse r = useCase.execute(new SearchBorrowingsQuery("RETURNED", true, null, 0, 10));

        assertThat(r.items()).isEmpty();
        assertThat(r.total()).isZero();
        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(10);
        verify(repository, never()).search(any());
    }

    @Test
    void ketQuaAnhXaSangResponseKemTenantVaFinesRong() {
        UUID tenant = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        BorrowingRecord record = BorrowingRecord.restore(BorrowingId.newId(), user,
                List.of(new BorrowingItem(copy, null, null)), TODAY.minusDays(20), TODAY.minusDays(6), 1, 14, tenant);
        when(repository.search(any())).thenReturn(new BorrowingSearchResult(List.of(record), 41));

        BorrowingPageResponse r = useCase.execute(new SearchBorrowingsQuery(null, null, null, 2, 1));

        assertThat(r.total()).isEqualTo(41);
        assertThat(r.page()).isEqualTo(2);
        assertThat(r.size()).isEqualTo(1);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).tenantId()).isEqualTo(tenant);
        assertThat(r.items().get(0).status()).isEqualTo("ACTIVE");
        assertThat(r.items().get(0).renewCount()).isEqualTo(1);
        assertThat(r.items().get(0).items()).hasSize(1);
        assertThat(r.items().get(0).fines()).isEmpty();
    }

    @Test
    void thamSoSaiBiTuChoiKhongKepSize() {
        assertThatThrownBy(() -> useCase.execute(new SearchBorrowingsQuery("OVERDUE", null, null, null, null)))
                .isInstanceOf(InvalidBorrowingQueryException.class);
        assertThatThrownBy(() -> useCase.execute(new SearchBorrowingsQuery(null, null, null, -1, null)))
                .isInstanceOf(InvalidBorrowingQueryException.class);
        assertThatThrownBy(() -> useCase.execute(new SearchBorrowingsQuery(null, null, null, 0, 101)))
                .isInstanceOf(InvalidBorrowingQueryException.class);
        assertThatThrownBy(() -> useCase.execute(new SearchBorrowingsQuery(null, null, null, 0, 0)))
                .isInstanceOf(InvalidBorrowingQueryException.class);
        verify(repository, never()).search(any());
    }

    @Test
    void size100LaToiDaChoPhep() {
        assertThat(searchedWith(new SearchBorrowingsQuery(null, null, null, 0, 100)).size()).isEqualTo(100);
    }
}
