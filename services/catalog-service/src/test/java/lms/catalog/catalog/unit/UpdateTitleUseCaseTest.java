package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.TitleResponse;
import lms.catalog.catalog.application.UpdateTitleCommand;
import lms.catalog.catalog.application.UpdateTitleUseCase;
import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpdateTitleUseCaseTest {

    private final Map<UUID, Title> store = new HashMap<>();
    private Title existing;
    private Title other;
    private UpdateTitleUseCase useCase;

    @BeforeEach
    void setUp() {
        existing = Title.create("Clean Code", "Martin", "111", BigDecimal.TEN);
        other = Title.create("Refactoring", "Fowler", "222", BigDecimal.TEN);
        store.put(existing.id(), existing);
        store.put(other.id(), other);

        TitleRepository titles = new TitleRepository() {
            public Title save(Title t) { store.put(t.id(), t); return t; }
            public Optional<Title> findById(UUID id) { return Optional.ofNullable(store.get(id)); }
            public boolean existsByIsbn(String isbn) {
                return store.values().stream().anyMatch(t -> isbn.equals(t.isbn()));
            }
            public boolean existsByIsbnAndIdNot(String isbn, UUID id) {
                return store.values().stream().anyMatch(t -> isbn.equals(t.isbn()) && !t.id().equals(id));
            }
            public List<Title> search(String keyword) { return List.of(); }
            public List<Title> findByIds(Collection<UUID> ids) { return List.of(); }
        };
        BookCopyRepository copies = new BookCopyRepository() {
            public BookCopy save(BookCopy c) { return c; }
            public Optional<BookCopy> findById(UUID id) { return Optional.empty(); }
            public Optional<BookCopy> findByIdForUpdate(UUID id) { return Optional.empty(); }
            public Optional<CopyDetails> findDetailsById(UUID id) { return Optional.empty(); }
            public Optional<CopyDetails> findDetailsByBarcode(String barcode) { return Optional.empty(); }
            public boolean existsByBarcode(String b) { return false; }
            public boolean titleExists(UUID id) { return true; }
            public List<BookCopy> findByTitleId(UUID id) { return List.of(); }
            public Map<UUID, Long> countByTitleIds(Collection<UUID> ids, CopyStatus s) {
                return Map.of(existing.id(), 4L);
            }
            public Set<UUID> findTitleIdsHavingStatus(CopyStatus s) { return Set.of(); }
        };
        useCase = new UpdateTitleUseCase(titles, new CopyAvailabilityQuery(copies));
    }

    @Test
    void suaCacTruongVaGiuIdKemSoBanSaoSanCo() {
        TitleResponse r = useCase.execute(new UpdateTitleCommand(existing.id(), "  Clean Code 2 ", "Uncle Bob",
                "333", new BigDecimal("99")));
        assertEquals(existing.id(), r.id());
        assertEquals("Clean Code 2", r.name());
        assertEquals("Uncle Bob", r.author());
        assertEquals("333", r.isbn());
        assertEquals(new BigDecimal("99"), r.coverPrice());
        assertEquals(4L, r.availableCopies());
        assertEquals("Clean Code 2", store.get(existing.id()).name());
    }

    @Test
    void giuNguyenIsbnCuKhongBaoTrungChinhMinh() {
        TitleResponse r = useCase.execute(new UpdateTitleCommand(existing.id(), "Tên mới", "Martin", "111",
                BigDecimal.ONE));
        assertEquals("111", r.isbn());
    }

    @Test
    void isbnTrongThiXoaIsbn() {
        TitleResponse r = useCase.execute(new UpdateTitleCommand(existing.id(), "A", "B", " ", BigDecimal.ZERO));
        assertNull(r.isbn());
    }

    @Test
    void isbnCuaDauSachKhacThiNemDuplicateVaKhongLuu() {
        assertThrows(DuplicateIsbnException.class, () -> useCase.execute(
                new UpdateTitleCommand(existing.id(), "A", "B", "222", BigDecimal.ONE)));
        assertEquals("Clean Code", store.get(existing.id()).name());
    }

    @Test
    void khongThayDauSachThiNemNotFound() {
        assertThrows(TitleNotFoundException.class, () -> useCase.execute(
                new UpdateTitleCommand(UUID.randomUUID(), "A", "B", null, BigDecimal.ONE)));
    }

    @Test
    void duLieuSaiTheoDomainNemInvalidVaKhongLuu() {
        UUID id = existing.id();
        assertThrows(InvalidTitleException.class, () -> useCase.execute(new UpdateTitleCommand(id, " ", "B", null, BigDecimal.ONE)));
        assertThrows(InvalidTitleException.class, () -> useCase.execute(new UpdateTitleCommand(id, "A", null, null, BigDecimal.ONE)));
        assertThrows(InvalidTitleException.class, () -> useCase.execute(new UpdateTitleCommand(id, "A", "B", null, new BigDecimal("-1"))));
        assertThrows(InvalidTitleException.class, () -> useCase.execute(new UpdateTitleCommand(id, "A", "B", null, null)));
        assertTrue(store.get(id) == existing);
    }

    @Test
    void suaThayThongTinBoSungVaThieuNghiaLaXoa() {
        TitleExtras extras = new TitleExtras("Phụ", 2020, "Lần 2", "vi", 200, TitleFormat.HARDCOVER, "Dịch giả",
                "Bộ", 3, "https://x.example/a.png", List.of("a", "A", "b"));
        TitleResponse r = useCase.execute(new UpdateTitleCommand(existing.id(), "A", "B", "111", BigDecimal.ONE,
                null, null, null, extras));
        assertEquals("Phụ", r.subtitle());
        assertEquals(2020, r.publicationYear());
        assertEquals(TitleFormat.HARDCOVER, r.format());
        assertEquals(List.of("a", "b"), r.tags());
        assertEquals(3, r.seriesNumber());
        assertEquals("Phụ", store.get(existing.id()).extras().subtitle());

        TitleResponse cleared = useCase.execute(new UpdateTitleCommand(existing.id(), "A", "B", "111", BigDecimal.ONE));
        assertNull(cleared.subtitle());
        assertNull(cleared.format());
        assertTrue(cleared.tags().isEmpty());
        assertTrue(store.get(existing.id()).extras().isEmpty());
    }
}
