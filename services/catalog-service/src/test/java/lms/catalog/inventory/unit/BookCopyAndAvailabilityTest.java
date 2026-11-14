package lms.catalog.inventory.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.catalog.inventory.application.AddCopyCommand;
import lms.catalog.inventory.application.AddCopyUseCase;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyStatus;
import lms.catalog.inventory.domain.CopyTitleNotFoundException;
import lms.catalog.inventory.domain.DuplicateBarcodeException;
import lms.catalog.inventory.domain.InvalidCopyException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BookCopyAndAvailabilityTest {

    static class FakeRepo implements BookCopyRepository {
        final List<BookCopy> copies = new ArrayList<>();
        final Set<UUID> titles = new HashSet<>();

        public BookCopy save(BookCopy c) { copies.removeIf(x -> x.id().equals(c.id())); copies.add(c); return c; }
        public java.util.Optional<BookCopy> findById(UUID id) { return copies.stream().filter(c -> c.id().equals(id)).findFirst(); }
        public boolean existsByBarcode(String b) { return copies.stream().anyMatch(c -> c.barcode().equals(b)); }
        public boolean titleExists(UUID id) { return titles.contains(id); }
        public List<BookCopy> findByTitleId(UUID id) { return copies.stream().filter(c -> c.titleId().equals(id)).toList(); }
        public Map<UUID, Long> countByTitleIds(Collection<UUID> ids, CopyStatus s) {
            return copies.stream().filter(c -> ids.contains(c.titleId()) && c.status() == s)
                    .collect(Collectors.groupingBy(BookCopy::titleId, HashMap::new, Collectors.counting()));
        }
        public Set<UUID> findTitleIdsHavingStatus(CopyStatus s) {
            return copies.stream().filter(c -> c.status() == s).map(BookCopy::titleId).collect(Collectors.toSet());
        }
    }

    @Test
    void banSaoMoiLaAvailable() {
        BookCopy c = BookCopy.register(UUID.randomUUID(), " BC-1 ");
        assertTrue(c.isAvailable());
        assertEquals("BC-1", c.barcode());
        assertThrows(InvalidCopyException.class, () -> BookCopy.register(UUID.randomUUID(), " "));
    }

    @Test
    void nhapBanSaoYeuCauDauSachTonTaiVaMaVachKhongTrung() {
        FakeRepo repo = new FakeRepo();
        List<lms.common.kernel.DomainEvent> published = new ArrayList<>();
        AddCopyUseCase useCase = new AddCopyUseCase(repo, published::add, java.time.Clock.systemUTC());
        UUID title = UUID.randomUUID();

        assertThrows(CopyTitleNotFoundException.class, () -> useCase.execute(new AddCopyCommand(title, "X1")));

        repo.titles.add(title);
        assertEquals(CopyStatus.AVAILABLE, useCase.execute(new AddCopyCommand(title, "X1")).status());
        assertThrows(DuplicateBarcodeException.class, () -> useCase.execute(new AddCopyCommand(title, "X1")));
        assertEquals(1, published.size());
        assertTrue(published.get(0) instanceof lms.catalog.inventory.domain.CopyAvailableEvent);
    }

    @Test
    void giuVaNhaBanSaoTheoVongDoiDatTruoc() {
        FakeRepo repo = new FakeRepo();
        List<lms.common.kernel.DomainEvent> published = new ArrayList<>();
        var clock = java.time.Clock.systemUTC();
        BookCopy copy = repo.save(BookCopy.register(UUID.randomUUID(), "H1"));
        var hold = new lms.catalog.inventory.application.HoldCopyUseCase(repo);
        var release = new lms.catalog.inventory.application.ReleaseCopyUseCase(repo, published::add, clock);

        hold.execute(copy.id());
        assertEquals(CopyStatus.RESERVED, repo.findById(copy.id()).orElseThrow().status());
        hold.execute(copy.id()); // idempotent
        assertEquals(CopyStatus.RESERVED, repo.findById(copy.id()).orElseThrow().status());

        release.execute(copy.id());
        assertEquals(CopyStatus.AVAILABLE, repo.findById(copy.id()).orElseThrow().status());
        assertEquals(1, published.size()); // phát lại CopyAvailable để người mới vào hàng đợi được giữ
        release.execute(copy.id()); // idempotent: đã AVAILABLE thì không phát thêm
        assertEquals(1, published.size());
    }

    @Test
    void khongGiuDuocBanSaoDaBiMuon() {
        BookCopy loaned = BookCopy.restore(UUID.randomUUID(), UUID.randomUUID(), "L1", CopyStatus.LOANED);
        assertEquals(false, loaned.hold());
        assertEquals(false, loaned.release());
    }
}
