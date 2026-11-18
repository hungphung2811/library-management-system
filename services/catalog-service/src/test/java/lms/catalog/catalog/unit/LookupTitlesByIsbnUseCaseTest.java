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
import lms.catalog.catalog.application.LookupTitlesByIsbnUseCase;
import lms.catalog.catalog.application.TitleLookupView;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import org.junit.jupiter.api.Test;

class LookupTitlesByIsbnUseCaseTest {

    private static class FakeRepo implements TitleRepository {
        final List<Title> stored;
        Collection<String> asked;
        int calls;

        FakeRepo(Title... titles) { this.stored = List.of(titles); }

        public Title save(Title t) { return t; }
        public Optional<Title> findById(UUID id) { return Optional.empty(); }
        public boolean existsByIsbn(String isbn) { return false; }
        public boolean existsByIsbnAndIdNot(String isbn, UUID id) { return false; }
        public List<Title> search(String keyword) { return List.of(); }
        public List<Title> findByIds(Collection<UUID> ids) { return List.of(); }
        @Override
        public List<Title> findByIsbns(Collection<String> isbns) {
            calls++;
            asked = isbns;
            return stored.stream().filter(t -> isbns.contains(t.isbn())).toList();
        }
    }

    @Test
    void traTheoThuTuYeuCauBoIsbnKhongThayTrungVaTrim() {
        Title a = Title.create("A", "Tac gia A", "111", new BigDecimal("100"));
        Title b = Title.create("B", "Tac gia B", "222", new BigDecimal("200"));
        FakeRepo repo = new FakeRepo(a, b);

        List<TitleLookupView> result = new LookupTitlesByIsbnUseCase(repo)
                .execute(List.of("222", "999", " 111 ", "222"));

        assertEquals(List.of(b.id(), a.id()), result.stream().map(TitleLookupView::id).toList());
        assertEquals(3, repo.asked.size());
        assertEquals(1, repo.calls);
    }

    @Test
    void danhSachRongHoacToanKhoangTrangKhongGoiRepository() {
        FakeRepo repo = new FakeRepo();
        LookupTitlesByIsbnUseCase useCase = new LookupTitlesByIsbnUseCase(repo);
        assertTrue(useCase.execute(List.of()).isEmpty());
        assertTrue(useCase.execute(List.of("  ")).isEmpty());
        assertEquals(0, repo.calls);
    }

    @Test
    void nam5000IsbnMotLanGoiVaQuaGioiHanBiTuChoi() {
        FakeRepo repo = new FakeRepo();
        LookupTitlesByIsbnUseCase useCase = new LookupTitlesByIsbnUseCase(repo);
        List<String> ok = new ArrayList<>();
        for (int i = 0; i < 5000; i++) ok.add("I" + i);
        useCase.execute(ok);
        assertEquals(1, repo.calls);
        ok.add("I5000");
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(ok));
    }
}
