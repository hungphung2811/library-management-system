package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.TitleResponse;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyStatus;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchTitlesUseCaseTest {

    @Test
    void timKiemKemSoBanSaoSanCoVaLocDauSachConSach() {
        Title withCopy = Title.create("Clean Code", "Robert C. Martin", null, BigDecimal.TEN);
        Title noCopy = Title.create("Design Patterns", "GoF", null, BigDecimal.TEN);

        TitleRepository titles = new TitleRepository() {
            public Title save(Title t) { return t; }
            public Optional<Title> findById(UUID id) { return Optional.empty(); }
            public boolean existsByIsbn(String isbn) { return false; }
            public List<Title> search(String keyword) { return List.of(withCopy, noCopy); }
            public List<Title> findByIds(Collection<UUID> ids) { return List.of(); }
        };
        BookCopyRepository copies = new BookCopyRepository() {
            public BookCopy save(BookCopy c) { return c; }
            public Optional<BookCopy> findById(UUID id) { return Optional.empty(); }
            public boolean existsByBarcode(String b) { return false; }
            public boolean titleExists(UUID id) { return true; }
            public List<BookCopy> findByTitleId(UUID id) { return List.of(); }
            public Map<UUID, Long> countByTitleIds(Collection<UUID> ids, CopyStatus s) {
                Map<UUID, Long> m = new HashMap<>();
                m.put(withCopy.id(), 2L);
                return m;
            }
            public Set<UUID> findTitleIdsHavingStatus(CopyStatus s) { return Set.of(withCopy.id()); }
        };
        SearchTitlesUseCase useCase = new SearchTitlesUseCase(titles, new CopyAvailabilityQuery(copies));

        List<TitleResponse> all = useCase.execute(null, false);
        assertEquals(2, all.size());
        assertEquals(2L, all.get(0).availableCopies());
        assertEquals(0L, all.get(1).availableCopies());

        List<TitleResponse> onlyAvailable = useCase.execute(null, true);
        assertEquals(1, onlyAvailable.size());
        assertEquals("Clean Code", onlyAvailable.get(0).name());
    }
}
