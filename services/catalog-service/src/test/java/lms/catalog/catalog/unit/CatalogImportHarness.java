package lms.catalog.catalog.unit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lms.catalog.catalog.application.imports.CatalogImportSchemas;
import lms.catalog.catalog.application.imports.ImportAuditPort;
import lms.catalog.catalog.application.imports.ReferenceCachePort;
import lms.catalog.catalog.application.imports.ReferenceChunkWriter;
import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.catalog.catalog.application.imports.TitleChunkWriter;
import lms.catalog.catalog.application.imports.TitleImportHandler;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceBatchConflictException;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleBatchConflictException;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.PublishInventoryEventPort;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyStatus;
import lms.common.kernel.DomainEvent;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportSchema;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportViews;
import lms.imports.engine.ImportActor;
import lms.imports.engine.ImportCommitExecutor;
import lms.imports.engine.ImportWorkflow;
import lms.imports.engine.InMemoryImportSessionStore;
import lms.imports.engine.UploadGate;
import lms.imports.xlsx.StreamingXlsxReader;
import lms.imports.xlsx.SxssfXlsxWriter;

/**
 * Khung chạy handler nhập catalog qua {@link ImportWorkflow} thật + {@link InMemoryImportSessionStore} với kho giả trong
 * bộ nhớ (không Spring, không DB): upload file .xlsx dựng bằng POI, confirm, đợi luồng nền xong.
 */
final class CatalogImportHarness {

    static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    final FakeReferences references = new FakeReferences();
    final FakeTitles titles = new FakeTitles();
    final FakeWorks works = new FakeWorks();
    final FakeCopies copiesRepo = new FakeCopies();
    final List<DomainEvent> events = new ArrayList<>();
    final List<String> audits = new ArrayList<>();
    final List<ReferenceKind> evicted = new ArrayList<>();

    final RegisterCopiesInBatchUseCase registerCopies = new RegisterCopiesInBatchUseCase(copiesRepo,
            (PublishInventoryEventPort) events::add, Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    final ImportAuditPort audit = (ctx, result) -> audits.add(result.type() + ":" + result.created() + "/"
            + result.skippedDuplicate() + "/" + result.failed());
    final ReferenceCachePort cache = evicted::add;
    final ReferenceChunkWriter referenceWriter = new ReferenceChunkWriter(references);
    final TitleChunkWriter titleWriter = new TitleChunkWriter(titles, works, references, registerCopies);

    final ReferenceImportHandler authors = new ReferenceImportHandler(ReferenceKind.AUTHOR,
            CatalogImportSchemas.authors(), references, referenceWriter, cache, audit);
    final ReferenceImportHandler publishers = new ReferenceImportHandler(ReferenceKind.PUBLISHER,
            CatalogImportSchemas.publishers(), references, referenceWriter, cache, audit);
    final ReferenceImportHandler categories = new ReferenceImportHandler(ReferenceKind.CATEGORY,
            CatalogImportSchemas.categories(), references, referenceWriter, cache, audit);
    final ReferenceImportHandler shelves = new ReferenceImportHandler(ReferenceKind.SHELF_LOCATION,
            CatalogImportSchemas.shelfLocations(), references, referenceWriter, cache, audit);
    final ReferenceImportHandler series = new ReferenceImportHandler(ReferenceKind.SERIES,
            CatalogImportSchemas.series(), references, referenceWriter, cache, audit);
    final TitleImportHandler titleHandler = new TitleImportHandler(titles, works, references, registerCopies, titleWriter,
            audit);

    final ImportLimits limits = ImportLimits.defaults();
    final ImportCommitExecutor executor = new ImportCommitExecutor(4);
    final ImportWorkflow workflow = new ImportWorkflow(new InMemoryImportSessionStore(limits, Clock.systemUTC()),
            new StreamingXlsxReader(limits), new SxssfXlsxWriter(limits), new UploadGate(2), executor, limits,
            Clock.systemUTC());

    final ImportActor actor = new ImportActor(TENANT, "alice", Set.of("LENDING"), false);
    final ImportActor actorNoLending = new ImportActor(TENANT, "bob", Set.of(), false);

    // ------------------------------------------------------------------ xlsx

    static Map<String, Object> row(Object... keyValues) {
        return CatalogImportRows.row(keyValues);
    }

    static byte[] xlsx(ImportSchema schema, Map<String, List<Map<String, Object>>> data) {
        return CatalogImportRows.xlsx(schema, data);
    }

    static byte[] single(ImportSchema schema, List<Map<String, Object>> rows) {
        return CatalogImportRows.single(schema, rows);
    }

    // ------------------------------------------------------------------ chạy

    ImportViews.SessionView upload(lms.imports.core.ImportHandler handler, byte[] file) {
        return workflow.upload(handler, actor, "file.xlsx", file);
    }

    ImportViews.SessionView upload(lms.imports.core.ImportHandler handler, ImportActor who, byte[] file) {
        return workflow.upload(handler, who, "file.xlsx", file);
    }

    ImportViews.RowsPage rows(lms.imports.core.ImportHandler handler, ImportViews.SessionView s) {
        return workflow.rows(handler, actor, s.importId(), new ImportViews.RowsQuery(null, null, 0, 200));
    }

    /** Confirm rồi đợi luồng nền xong (COMMITTED hoặc COMMIT_FAILED). */
    ImportViews.SessionView confirmAndWait(lms.imports.core.ImportHandler handler, ImportActor who, UUID importId) {
        workflow.confirm(handler, who, importId);
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ImportViews.SessionView v = workflow.get(handler, who, importId);
            if (v.status() == ImportSessionStatus.COMMITTED || v.status() == ImportSessionStatus.COMMIT_FAILED) return v;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Confirm không xong trong 15 giây");
    }

    ImportViews.SessionView confirmAndWait(lms.imports.core.ImportHandler handler, UUID importId) {
        return confirmAndWait(handler, actor, importId);
    }

    void close() {
        executor.close();
    }

    // ------------------------------------------------------------------ kho giả

    static final class FakeReferences implements ReferenceRepository {
        final Map<ReferenceKind, Map<String, Reference>> byKind = new HashMap<>();
        final AtomicInteger findByNameKeysCalls = new AtomicInteger();
        final AtomicInteger insertCalls = new AtomicInteger();
        /** Đặt giá trị khác null: lần insertAll nhiều dòng kế tiếp sẽ "bị người khác tạo mất" khóa này rồi ném xung đột. */
        volatile Reference raceWinner;

        Map<String, Reference> kind(ReferenceKind k) {
            return byKind.computeIfAbsent(k, x -> new LinkedHashMap<>());
        }

        void seed(Reference r) {
            kind(r.kind()).put(r.nameKey(), r);
        }

        List<Reference> all(ReferenceKind k) {
            return new ArrayList<>(kind(k).values());
        }

        @Override
        public Reference save(Reference reference) {
            seed(reference);
            return reference;
        }

        @Override
        public Optional<Reference> findById(ReferenceKind kind, UUID id) {
            return kind(kind).values().stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public List<Reference> findByIds(ReferenceKind kind, Collection<UUID> ids) {
            return kind(kind).values().stream().filter(r -> ids.contains(r.id())).toList();
        }

        @Override
        public boolean existsByNameKey(ReferenceKind kind, String nameKey, UUID excludeId) {
            return kind(kind).containsKey(nameKey);
        }

        @Override
        public boolean hasChildren(UUID categoryId) {
            return false;
        }

        @Override
        public Page search(ReferenceKind kind, String keyword, Boolean active, UUID parentId, int page, int size) {
            return new Page(List.of(), 0);
        }

        @Override
        public List<Reference> options(ReferenceKind kind, String keyword, int limit) {
            return List.of();
        }

        @Override
        public List<Reference> findByNameKeys(ReferenceKind kind, Collection<String> nameKeys) {
            findByNameKeysCalls.incrementAndGet();
            List<Reference> out = new ArrayList<>();
            for (String k : nameKeys) {
                Reference r = kind(kind).get(k);
                if (r != null) out.add(r);
            }
            return out;
        }

        @Override
        public synchronized void insertAll(Collection<Reference> references) {
            insertCalls.incrementAndGet();
            Reference winner = raceWinner;
            if (winner != null && references.size() > 1) {
                raceWinner = null;
                seed(winner);
                throw new ReferenceBatchConflictException(new RuntimeException("duplicate key"));
            }
            for (Reference r : references) {
                if (kind(r.kind()).containsKey(r.nameKey())) {
                    throw new ReferenceBatchConflictException(new RuntimeException("duplicate key"));
                }
            }
            references.forEach(this::seed);
        }
    }

    static final class FakeTitles implements TitleRepository {
        final Map<UUID, Title> byId = new LinkedHashMap<>();
        final AtomicInteger isbnLookups = new AtomicInteger();
        volatile String raceIsbn;

        void seed(Title t) {
            byId.put(t.id(), t);
        }

        Title byIsbn(String isbn) {
            return byId.values().stream().filter(t -> isbn.equals(t.isbn())).findFirst().orElse(null);
        }

        @Override
        public Title save(Title title) {
            seed(title);
            return title;
        }

        @Override
        public Optional<Title> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean existsByIsbn(String isbn) {
            return byIsbn(isbn) != null;
        }

        @Override
        public boolean existsByIsbnAndIdNot(String isbn, UUID id) {
            return false;
        }

        @Override
        public List<Title> search(String keyword) {
            return new ArrayList<>(byId.values());
        }

        @Override
        public List<Title> findByIds(Collection<UUID> ids) {
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public Map<String, UUID> findIdsByIsbns(Collection<String> isbns) {
            isbnLookups.incrementAndGet();
            Map<String, UUID> out = new HashMap<>();
            for (Title t : byId.values()) if (t.isbn() != null && isbns.contains(t.isbn())) out.put(t.isbn(), t.id());
            return out;
        }

        @Override
        public synchronized void insertAll(Collection<Title> titles) {
            String race = raceIsbn;
            if (race != null && titles.stream().anyMatch(t -> race.equals(t.isbn()))) {
                raceIsbn = null;
                seed(Title.create("Người khác tạo", "X", race, java.math.BigDecimal.ONE));
                throw new TitleBatchConflictException(new RuntimeException("duplicate key"));
            }
            titles.forEach(this::seed);
        }
    }

    /** Kho tác phẩm giả; đếm số lần tra theo lô để test chứng minh không N+1. */
    static final class FakeWorks implements WorkRepository {
        final Map<UUID, Work> byId = new LinkedHashMap<>();
        final AtomicInteger findByIdsCalls = new AtomicInteger();
        final AtomicInteger insertAllCalls = new AtomicInteger();

        void seed(Work w) {
            byId.put(w.id(), w);
        }

        @Override
        public Work save(Work work) {
            seed(work);
            return work;
        }

        @Override
        public synchronized void insertAll(Collection<Work> works) {
            insertAllCalls.incrementAndGet();
            works.forEach(this::seed);
        }

        @Override
        public Optional<Work> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Work> findByIds(Collection<UUID> ids) {
            findByIdsCalls.incrementAndGet();
            return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public void delete(UUID id) {
            byId.remove(id);
        }

        @Override
        public List<UUID> findIdsBySeries(UUID seriesId) {
            return byId.values().stream().filter(w -> seriesId.equals(w.seriesId())).map(Work::id).toList();
        }

        @Override
        public Page search(Criteria criteria, int page, int size) {
            return new Page(List.of(), 0);
        }
    }

    static final class FakeCopies implements BookCopyRepository {
        final Map<String, BookCopy> byBarcode = new LinkedHashMap<>();
        final AtomicInteger barcodeLookups = new AtomicInteger();

        void seed(BookCopy c) {
            byBarcode.put(c.barcode(), c);
        }

        @Override
        public BookCopy save(BookCopy copy) {
            seed(copy);
            return copy;
        }

        @Override
        public Optional<BookCopy> findById(UUID id) {
            return byBarcode.values().stream().filter(c -> c.id().equals(id)).findFirst();
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
            return byBarcode.containsKey(barcode);
        }

        @Override
        public boolean titleExists(UUID titleId) {
            return true;
        }

        @Override
        public List<BookCopy> findByTitleId(UUID titleId) {
            return byBarcode.values().stream().filter(c -> c.titleId().equals(titleId)).toList();
        }

        @Override
        public Map<UUID, Long> countByTitleIds(Collection<UUID> titleIds, CopyStatus status) {
            return Map.of();
        }

        @Override
        public Set<UUID> findTitleIdsHavingStatus(CopyStatus status) {
            return Set.of();
        }

        @Override
        public Set<String> findExistingBarcodes(Collection<String> barcodes) {
            barcodeLookups.incrementAndGet();
            Set<String> out = new java.util.HashSet<>();
            for (String b : barcodes) if (byBarcode.containsKey(b)) out.add(b);
            return out;
        }

        @Override
        public void insertAll(Collection<BookCopy> copies) {
            copies.forEach(this::seed);
        }
    }

    static Instant now() {
        return Instant.now();
    }
}
