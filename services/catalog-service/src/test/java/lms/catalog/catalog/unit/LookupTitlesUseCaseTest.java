package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.catalog.catalog.application.LookupTitlesUseCase;
import lms.catalog.catalog.application.TitleLookupView;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import org.junit.jupiter.api.Test;

class LookupTitlesUseCaseTest {

    private static class FakeRepo implements TitleRepository {
        final List<Title> stored;
        Collection<UUID> askedIds;
        int calls;

        FakeRepo(Title... titles) { this.stored = List.of(titles); }

        public Title save(Title t) { return t; }
        public Optional<Title> findById(UUID id) { return Optional.empty(); }
        public boolean existsByIsbn(String isbn) { return false; }
        public boolean existsByIsbnAndIdNot(String isbn, UUID id) { return false; }
        public List<Title> search(String keyword) { return List.of(); }
        public List<Title> findByIds(Collection<UUID> ids) {
            calls++;
            askedIds = ids;
            return stored.stream().filter(t -> ids.contains(t.id())).toList();
        }
    }

    @Test
    void traTheoThuTuYeuCauBoIdKhongThayVaIdTrung() {
        Title a = Title.create("A", "Tac gia A", "111", new BigDecimal("100"));
        Title b = Title.create("B", "Tac gia B", null, new BigDecimal("200"));
        UUID missing = UUID.randomUUID();
        FakeRepo repo = new FakeRepo(a, b);

        List<TitleLookupView> result = new LookupTitlesUseCase(repo).execute(List.of(b.id(), missing, a.id(), b.id()));

        assertEquals(List.of(b.id(), a.id()), result.stream().map(TitleLookupView::id).toList());
        assertEquals("A", result.get(1).name());
        assertEquals("Tac gia A", result.get(1).author());
        assertEquals("111", result.get(1).isbn());
        assertEquals(new BigDecimal("100"), result.get(1).coverPrice());
        assertEquals(3, repo.askedIds.size());
        assertEquals(1, repo.calls);
    }

    @Test
    void danhSachRongKhongGoiRepository() {
        FakeRepo repo = new FakeRepo();
        assertTrue(new LookupTitlesUseCase(repo).execute(List.of()).isEmpty());
        assertEquals(0, repo.calls);
    }

    @Test
    void quaGioiHanHoacIdNullBiTuChoi() {
        LookupTitlesUseCase useCase = new LookupTitlesUseCase(new FakeRepo());
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) tooMany.add(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(tooMany));
        List<UUID> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> useCase.execute(withNull));
    }
}
