package lms.catalog.catalog.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.infrastructure.demo.CatalogDemoSeedStep;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyStatus;
import lms.catalog.inventory.infrastructure.demo.InventoryDemoSeedStep;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.BorrowingPlan;
import lms.demo.plan.DemoPlan.LoanItemPlan;
import lms.demo.plan.DemoPlan.ReservationPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** Bước seed demo của catalog (ADR-0018): số lượng, trạng thái khớp kế hoạch, idempotent, không đè dữ liệu có sẵn. */
class DemoSeedStepsTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final DemoPlan PLAN = DemoPlan.generate(LocalDate.of(2026, 11, 17));

    final FakeReferences references = new FakeReferences();
    final FakeTitles titles = new FakeTitles();
    final FakeCopies copies = new FakeCopies(titles);
    final CatalogDemoSeedStep catalogStep = new CatalogDemoSeedStep(references, titles, TransactionOperations.withoutTransaction());
    final InventoryDemoSeedStep inventoryStep = new InventoryDemoSeedStep(copies, TransactionOperations.withoutTransaction());

    private static DemoSeedContext context(DemoPlan plan) {
        return new DemoSeedContext(TENANT, plan, "x", Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    @Test
    void thuTuBuocDauSachChayTruocBanSao() {
        assertThat(catalogStep.order()).isLessThan(inventoryStep.order());
    }

    @Test
    void taoDuDauSachVaBanSaoTheoKeHoach() {
        DemoSeedStep.SeedResult t = catalogStep.run(context(PLAN));
        DemoSeedStep.SeedResult c = inventoryStep.run(context(PLAN));

        assertThat(titles.rows).hasSize(PLAN.titles().size()).hasSizeGreaterThanOrEqualTo(60);
        assertThat(copies.rows).hasSize(PLAN.copies().size()).hasSizeGreaterThanOrEqualTo(150);
        assertThat(t.skipped()).isZero();
        assertThat(c).isEqualTo(DemoSeedStep.SeedResult.of(PLAN.copies().size(), 0));
        // đủ 5 trạng thái, số lượng từng trạng thái đúng kế hoạch
        Map<CopyStatus, Long> byStatus = copies.rows.values().stream()
                .collect(Collectors.groupingBy(BookCopy::status, Collectors.counting()));
        assertThat(byStatus).containsOnlyKeys(CopyStatus.values());
        for (DemoPlan.CopyStatus s : DemoPlan.CopyStatus.values()) {
            long expected = PLAN.copies().stream().filter(x -> x.status() == s).count();
            assertThat(byStatus.get(CopyStatus.valueOf(s.name()))).isEqualTo(expected);
        }
    }

    @Test
    void dauSachDungTenIsbnTacGiaVaCoTheLoaiTacGiaChuan() {
        catalogStep.run(context(PLAN));

        for (DemoPlan.TitlePlan p : PLAN.titles()) {
            Title t = titles.rows.get(p.id());
            assertThat(t).isNotNull();
            assertThat(t.name()).isEqualTo(p.name());
            assertThat(t.author()).isEqualTo(p.author());
            assertThat(t.isbn()).isEqualTo(p.isbn());
            assertThat(t.coverPrice().signum()).isPositive();
            assertThat(t.categoryId()).isNotNull();
            assertThat(references.rows.get(ReferenceKind.CATEGORY).get(t.categoryId()).name()).isEqualTo(p.category());
            assertThat(t.authorIds()).hasSize(1);
            assertThat(references.rows.get(ReferenceKind.AUTHOR).get(t.authorIds().get(0)).name()).isEqualTo(p.author());
        }
        assertThat(titles.rows.values().stream().map(Title::isbn).collect(Collectors.toSet())).hasSize(PLAN.titles().size());
        assertThat(references.rows.get(ReferenceKind.CATEGORY)).hasSize(
                PLAN.titles().stream().map(DemoPlan.TitlePlan::category).collect(Collectors.toSet()).size());
    }

    @Test
    void banSaoLoanedKhopDongMuonChuaTraVaReservedKhopDatTruoc() {
        catalogStep.run(context(PLAN));
        inventoryStep.run(context(PLAN));

        Set<UUID> openLoanCopies = PLAN.borrowings().stream().filter(BorrowingPlan::isActive)
                .flatMap(b -> b.items().stream()).filter(i -> !i.isReturned()).map(LoanItemPlan::copyId)
                .collect(Collectors.toSet());
        Set<UUID> loaned = idsWithStatus(CopyStatus.LOANED);
        assertThat(loaned).isEqualTo(openLoanCopies);

        Set<UUID> reserved = PLAN.reservations().stream().map(ReservationPlan::copyId).collect(Collectors.toSet());
        assertThat(idsWithStatus(CopyStatus.RESERVED)).isEqualTo(reserved);
        for (BookCopy c : copies.rows.values()) {
            assertThat(c.titleId()).isIn(titles.rows.keySet());
        }
    }

    @Test
    void chayLaiKhongNhanDoiVaKhongLoi() {
        catalogStep.run(context(PLAN));
        inventoryStep.run(context(PLAN));
        int titleCount = titles.rows.size();
        int copyCount = copies.rows.size();
        int refCount = references.rows.values().stream().mapToInt(Map::size).sum();

        DemoSeedStep.SeedResult t = catalogStep.run(context(PLAN));
        DemoSeedStep.SeedResult c = inventoryStep.run(context(PLAN));

        assertThat(t.created()).isZero();
        assertThat(t.skipped()).isEqualTo(refCount + titleCount);
        assertThat(c).isEqualTo(DemoSeedStep.SeedResult.of(0, copyCount));
        assertThat(titles.rows).hasSize(titleCount);
        assertThat(copies.rows).hasSize(copyCount);
        assertThat(references.rows.values().stream().mapToInt(Map::size).sum()).isEqualTo(refCount);
    }

    @Test
    void chayLaiKhongDeTrangThaiBanSaoDaDoi() {
        catalogStep.run(context(PLAN));
        inventoryStep.run(context(PLAN));
        BookCopy loaned = copies.rows.values().stream().filter(c -> c.status() == CopyStatus.LOANED).findFirst().orElseThrow();
        loaned.changeStatus(CopyStatus.AVAILABLE);

        inventoryStep.run(context(PLAN));

        assertThat(copies.rows.get(loaned.id()).status()).isEqualTo(CopyStatus.AVAILABLE);
    }

    @Test
    void isbnTrungVoiDauSachKhacIdThiBoQuaKhongLoi() {
        DemoPlan.TitlePlan first = PLAN.titles().get(0);
        UUID other = UUID.randomUUID();
        titles.rows.put(other, Title.restore(other, "Sach co san", "Ai do", first.isbn(), java.math.BigDecimal.TEN));

        DemoSeedStep.SeedResult r = catalogStep.run(context(PLAN));

        assertThat(titles.rows).doesNotContainKey(first.id());
        assertThat(titles.rows).hasSize(PLAN.titles().size());
        assertThat(r.skipped()).isEqualTo(1);
    }

    @Test
    void banSaoMaVachTrungBoQuaConBanSaoThieuDauSachThiLoiRoRang() {
        // chưa chạy bước đầu sách: bản sao không có đầu sách để tham chiếu
        assertThatThrownBy(() -> inventoryStep.run(context(PLAN)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("catalog.titles");

        catalogStep.run(context(PLAN));
        DemoPlan.CopyPlan p = PLAN.copies().get(0);
        UUID other = UUID.randomUUID();
        copies.rows.put(other, BookCopy.restore(other, p.titleId(), p.barcode(), CopyStatus.AVAILABLE));

        DemoSeedStep.SeedResult r = inventoryStep.run(context(PLAN));

        assertThat(r.skipped()).isEqualTo(1);
        assertThat(copies.rows).doesNotContainKey(p.id()).hasSize(PLAN.copies().size());
    }

    @Test
    void cauTrucKhongDoiGiuaHaiNgayChayKhacNhau() {
        DemoPlan other = DemoPlan.generate(LocalDate.of(2026, 12, 20));
        catalogStep.run(context(PLAN));
        inventoryStep.run(context(PLAN));
        int titleCount = titles.rows.size();
        int copyCount = copies.rows.size();

        DemoSeedStep.SeedResult t = catalogStep.run(context(other));
        DemoSeedStep.SeedResult c = inventoryStep.run(context(other));

        assertThat(t.created()).isZero();
        assertThat(c.created()).isZero();
        assertThat(titles.rows).hasSize(titleCount);
        assertThat(copies.rows).hasSize(copyCount);
    }

    private Set<UUID> idsWithStatus(CopyStatus status) {
        return copies.rows.values().stream().filter(c -> c.status() == status).map(BookCopy::id).collect(Collectors.toSet());
    }

    // ---- Bản giả trong bộ nhớ ----

    static class FakeReferences implements ReferenceRepository {
        final Map<ReferenceKind, Map<UUID, Reference>> rows = new LinkedHashMap<>();

        FakeReferences() {
            for (ReferenceKind k : ReferenceKind.values()) rows.put(k, new LinkedHashMap<>());
        }

        @Override
        public Reference save(Reference r) {
            rows.get(r.kind()).put(r.id(), r);
            return r;
        }

        @Override
        public Optional<Reference> findById(ReferenceKind kind, UUID id) {
            return Optional.ofNullable(rows.get(kind).get(id));
        }

        @Override
        public List<Reference> findByIds(ReferenceKind kind, Collection<UUID> ids) {
            return ids.stream().map(rows.get(kind)::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public boolean existsByNameKey(ReferenceKind kind, String nameKey, UUID excludeId) {
            return rows.get(kind).values().stream().anyMatch(r -> r.nameKey().equals(nameKey) && !r.id().equals(excludeId));
        }

        @Override
        public boolean hasChildren(UUID categoryId) {
            return false;
        }

        @Override
        public Page search(ReferenceKind kind, String keyword, Boolean active, UUID parentId, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Reference> options(ReferenceKind kind, String keyword, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeTitles implements TitleRepository {
        final Map<UUID, Title> rows = new LinkedHashMap<>();

        @Override
        public Title save(Title title) {
            rows.put(title.id(), title);
            return title;
        }

        @Override
        public Optional<Title> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean existsByIsbn(String isbn) {
            return rows.values().stream().anyMatch(t -> isbn.equals(t.isbn()));
        }

        @Override
        public boolean existsByIsbnAndIdNot(String isbn, UUID id) {
            return rows.values().stream().anyMatch(t -> isbn.equals(t.isbn()) && !t.id().equals(id));
        }

        @Override
        public List<Title> search(String keyword) {
            return new ArrayList<>(rows.values());
        }

        @Override
        public List<Title> findByIds(Collection<UUID> ids) {
            return ids.stream().map(rows::get).filter(java.util.Objects::nonNull).toList();
        }
    }

    static class FakeCopies implements BookCopyRepository {
        final Map<UUID, BookCopy> rows = new LinkedHashMap<>();
        private final FakeTitles titles;

        FakeCopies(FakeTitles titles) {
            this.titles = titles;
        }

        @Override
        public BookCopy save(BookCopy copy) {
            rows.put(copy.id(), copy);
            return copy;
        }

        @Override
        public Optional<BookCopy> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<BookCopy> findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public Optional<CopyDetails> findDetailsById(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<CopyDetails> findDetailsByBarcode(String barcode) {
            return Optional.empty();
        }

        @Override
        public boolean existsByBarcode(String barcode) {
            return rows.values().stream().anyMatch(c -> c.barcode().equals(barcode));
        }

        @Override
        public boolean titleExists(UUID titleId) {
            return titles.rows.containsKey(titleId);
        }

        @Override
        public List<BookCopy> findByTitleId(UUID titleId) {
            return rows.values().stream().filter(c -> c.titleId().equals(titleId)).toList();
        }

        @Override
        public Map<UUID, Long> countByTitleIds(Collection<UUID> titleIds, CopyStatus status) {
            return rows.values().stream().filter(c -> titleIds.contains(c.titleId()) && c.status() == status)
                    .collect(Collectors.groupingBy(BookCopy::titleId, Collectors.counting()));
        }

        @Override
        public Set<UUID> findTitleIdsHavingStatus(CopyStatus status) {
            return rows.values().stream().filter(c -> c.status() == status).map(BookCopy::titleId).collect(Collectors.toCollection(HashSet::new));
        }
    }
}
