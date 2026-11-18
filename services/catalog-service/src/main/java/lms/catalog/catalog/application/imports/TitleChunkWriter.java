package lms.catalog.catalog.application.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.imports.TitleRows.RefLookups;
import lms.catalog.catalog.application.imports.TitleRows.TitleDraft;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.inventory.application.CopyDraft;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase.CreatedCopy;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase.NewCopy;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportRow;
import lms.imports.core.OutcomeStatus;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi MỘT chunk đầu sách + bản sao trong MỘT giao dịch (ADR-0024 mục 3.6, 5.6): kiểm lại theo lô với DB hiện tại
 * (ISBN, tham chiếu, mã vạch: mỗi loại một truy vấn cho cả chunk), ghi đầu sách trước rồi bản sao. Số câu SQL không
 * phụ thuộc số dòng (ngoài chia lô 1.000 phần tử và batch JDBC).
 * Ném {@link lms.catalog.catalog.domain.TitleBatchConflictException} hoặc
 * {@link lms.catalog.inventory.application.CopyWriteConflictException} khi trùng khóa do ghi đồng thời.
 */
public class TitleChunkWriter {

    /** Kết quả một chunk và các bản sao mới (để phát sự kiện sau commit). */
    public record Outcome(ChunkResult result, List<CreatedCopy> createdCopies) {
    }

    private final TitleRepository titles;
    private final ReferenceRepository references;
    private final RegisterCopiesInBatchUseCase copies;
    private final WorkRepository works;

    public TitleChunkWriter(TitleRepository titles, WorkRepository works, ReferenceRepository references,
                            RegisterCopiesInBatchUseCase copies) {
        this.works = works;
        this.titles = titles;
        this.references = references;
        this.copies = copies;
    }

    @Transactional
    public Outcome write(List<ImportRow> chunk, boolean lendingEnabled) {
        int n = chunk.size();
        RowOutcome[] outcomes = new RowOutcome[n];
        Map<Integer, TitleDraft> drafts = new java.util.LinkedHashMap<>();
        List<Integer> copyRows = new ArrayList<>();

        Set<String> isbns = new HashSet<>();
        for (int i = 0; i < n; i++) {
            ImportRow row = chunk.get(i);
            if (CatalogImportSchemas.SHEET_COPIES.equals(row.sheet())) {
                copyRows.add(i);
                String isbn = ImportValues.text(row, "isbn");
                if (isbn != null) isbns.add(isbn);
                continue;
            }
            try {
                TitleDraft d = TitleRows.draft(row);
                drafts.put(i, d);
                if (d.title().isbn() != null) isbns.add(d.title().isbn());
            } catch (RowProblem p) {
                outcomes[i] = failed(row, p.toIssue());
            }
        }

        Map<String, UUID> dbIsbns = isbns.isEmpty() ? Map.of() : titles.findIdsByIsbns(isbns);
        Set<String> shelfCodes = new HashSet<>();
        if (lendingEnabled) {
            for (int i : copyRows) {
                String code = ImportValues.text(chunk.get(i), "shelfLocationCode");
                if (code != null) shelfCodes.add(code);
            }
        }
        RefLookups refs = RefLookups.load(references, drafts.values(), shelfCodes);

        Map<String, UUID> newIds = new HashMap<>();
        Set<String> failedIsbns = new HashSet<>();
        List<Title> toInsert = new ArrayList<>();
        List<Work> newWorks = new ArrayList<>();
        Map<String, Work> workByIsbn = new HashMap<>(); // ISBN ghi trong chunk này -> tác phẩm của ấn bản đó
        DbTargets dbTargets = DbTargets.load(drafts.values(), titles, works, references);
        for (Map.Entry<Integer, TitleDraft> e : drafts.entrySet()) {
            int i = e.getKey();
            ImportRow row = chunk.get(i);
            TitleDraft d = e.getValue();
            String isbn = d.title().isbn();
            if (isbn != null && (dbIsbns.containsKey(isbn) || newIds.containsKey(isbn))) {
                outcomes[i] = RowOutcome.skipped(row,
                        RowIssue.error("isbn", TitleRows.ALREADY_EXISTS, "ISBN đã tồn tại khi ghi, bỏ qua"));
                continue;
            }
            List<RowIssue> issues = TitleRows.refIssues(d, refs);
            if (!issues.isEmpty()) {
                outcomes[i] = new RowOutcome(row.sheet(), row.rowNumber(), OutcomeStatus.FAILED, issues);
                if (isbn != null) failedIsbns.add(isbn);
                continue;
            }
            Placed placed;
            try {
                placed = placeInWork(d, TitleRows.classified(d, refs), refs, workByIsbn, dbTargets, failedIsbns);
            } catch (RowProblem p) {
                outcomes[i] = failed(row, p.toIssue());
                if (isbn != null) failedIsbns.add(isbn);
                continue;
            }
            Title t = placed.title();
            if (placed.created() != null) newWorks.add(placed.created());
            toInsert.add(t);
            if (isbn != null) {
                newIds.put(isbn, t.id());
                workByIsbn.put(isbn, placed.work());
            }
            outcomes[i] = RowOutcome.created(row);
        }
        if (!newWorks.isEmpty()) works.insertAll(newWorks);
        if (!toInsert.isEmpty()) titles.insertAll(toInsert);

        List<NewCopy> newCopies = new ArrayList<>();
        List<Integer> newCopyRows = new ArrayList<>();
        for (int i : copyRows) {
            ImportRow row = chunk.get(i);
            if (!lendingEnabled) {
                outcomes[i] = failed(row, RowIssue.error(null, "MODULE_NOT_ENABLED",
                        "Tenant chưa bật module LENDING nên không nhập được bản sao"));
                continue;
            }
            String isbn = ImportValues.text(row, "isbn");
            UUID titleId = isbn == null ? null : newIds.getOrDefault(isbn, dbIsbns.get(isbn));
            if (titleId == null) {
                boolean titleFailed = isbn != null && failedIsbns.contains(isbn);
                outcomes[i] = failed(row, titleFailed
                        ? RowIssue.error("isbn", "TITLE_ROW_INVALID", "Dòng đầu sách tương ứng không ghi được")
                        : RowIssue.error("isbn", "TITLE_NOT_FOUND", "Không tìm thấy đầu sách có ISBN này"));
                continue;
            }
            List<RowIssue> shelfIssues = TitleRows.shelfIssues(row, refs);
            if (!shelfIssues.isEmpty()) {
                outcomes[i] = new RowOutcome(row.sheet(), row.rowNumber(), OutcomeStatus.FAILED, shelfIssues);
                continue;
            }
            try {
                CopyDraft draft = TitleRows.copyDraft(row, TitleRows.shelfId(row, refs));
                String problem = copies.check(draft);
                if (problem != null) {
                    outcomes[i] = failed(row, RowIssue.error(null, "INVALID_COPY", problem));
                    continue;
                }
                newCopies.add(new NewCopy(titleId, draft));
                newCopyRows.add(i);
            } catch (RowProblem p) {
                outcomes[i] = failed(row, p.toIssue());
            }
        }

        List<CreatedCopy> created = List.of();
        if (!newCopies.isEmpty()) {
            RegisterCopiesInBatchUseCase.Result r = copies.registerAll(newCopies);
            created = r.created();
            for (int k = 0; k < newCopyRows.size(); k++) {
                int i = newCopyRows.get(k);
                ImportRow row = chunk.get(i);
                outcomes[i] = r.skippedDuplicateIndexes().contains(k)
                        ? RowOutcome.skipped(row, RowIssue.error("barcode", TitleRows.ALREADY_EXISTS,
                                "Mã vạch đã tồn tại khi ghi, bỏ qua"))
                        : RowOutcome.created(row);
            }
        }
        return new Outcome(ChunkResult.of(List.of(outcomes)), created);
    }

    /** Ấn bản đã gắn tác phẩm; {@code created} khác null khi tác phẩm là tác phẩm MỚI tạo cho dòng này. */
    private record Placed(Title title, Work work, Work created) {
    }

    /** Ấn bản đích của cột sameWorkAsIsbn đã có trong DB (kể cả vừa ghi ở chunk trước): tra theo lô, không theo dòng. */
    private record DbTargets(Map<String, Title> titles, Map<UUID, Work> works, Map<UUID, String> seriesNames) {

        static DbTargets load(java.util.Collection<TitleDraft> drafts, TitleRepository titles, WorkRepository works,
                              ReferenceRepository references) {
            Set<String> isbns = new HashSet<>();
            for (TitleDraft d : drafts) if (d.work().sameWorkAsIsbn() != null) isbns.add(d.work().sameWorkAsIsbn());
            if (isbns.isEmpty()) return new DbTargets(Map.of(), Map.of(), Map.of());
            Map<String, Title> byIsbn = new HashMap<>();
            for (Title t : titles.findByIsbns(isbns)) byIsbn.put(t.isbn(), t);
            Set<UUID> workIds = new HashSet<>();
            for (Title t : byIsbn.values()) if (t.workId() != null) workIds.add(t.workId());
            Map<UUID, Work> byId = new HashMap<>();
            if (!workIds.isEmpty()) for (Work w : works.findByIds(workIds)) byId.put(w.id(), w);
            Set<UUID> seriesIds = new HashSet<>();
            for (Work w : byId.values()) if (w.seriesId() != null) seriesIds.add(w.seriesId());
            Map<UUID, String> names = new HashMap<>();
            if (!seriesIds.isEmpty()) {
                for (var r : references.findByIds(ReferenceKind.SERIES, seriesIds)) names.put(r.id(), r.name());
            }
            return new DbTargets(byIsbn, byId, names);
        }
    }

    /**
     * Gắn ấn bản vào tác phẩm. Không có sameWorkAsIsbn: tạo tác phẩm mới 1:1 từ trường của dòng. Có: dùng tác phẩm của ấn bản
     * đích (ghi trong chunk này hoặc đã có trong DB), ấn bản mang trường cấp tác phẩm của nó (ghi gương cột cũ, mục 4.3).
     */
    private Placed placeInWork(TitleDraft d, Title classified, RefLookups refs, Map<String, Work> workByIsbn,
                               DbTargets db, Set<String> failedIsbns) {
        String same = d.work().sameWorkAsIsbn();
        if (same == null) {
            Work work = TitleRows.newWork(d, classified, refs);
            String seriesName = work.seriesId() == null ? null
                    : refs.series().get(lms.catalog.catalog.domain.Reference.keyOf(d.seriesName())).name();
            Title t = classified.withWorkLevel(work.categoryId(), work.authorIds(), seriesName, work.seriesNumber())
                    .withWork(work.id());
            return new Placed(t, work, work);
        }
        Work target = workByIsbn.get(same);
        String seriesName = null;
        if (target != null) {
            seriesName = target.seriesId() == null ? null : seriesNameOf(target.seriesId(), refs, db);
        } else {
            Title dbTitle = db.titles().get(same);
            target = dbTitle == null || dbTitle.workId() == null ? null : db.works().get(dbTitle.workId());
            if (target == null) {
                boolean rowFailed = failedIsbns.contains(same) || dbTitle == null;
                throw new RowProblem("sameWorkAsIsbn", rowFailed ? WorkRefResolver.ROW_INVALID : WorkRefResolver.NOT_FOUND,
                        "Không gắn được vào tác phẩm của ấn bản ISBN " + same);
            }
            seriesName = target.seriesId() == null ? null : seriesNameOf(target.seriesId(), refs, db);
        }
        Title t = classified.withWork(target.id())
                .withWorkLevel(target.categoryId(), target.authorIds(), seriesName, target.seriesNumber());
        return new Placed(t, target, null);
    }

    private static String seriesNameOf(UUID seriesId, RefLookups refs, DbTargets db) {
        String name = db.seriesNames().get(seriesId);
        if (name != null) return name;
        for (var r : refs.series().values()) if (r.id().equals(seriesId)) return r.name();
        return null;
    }

    private static RowOutcome failed(ImportRow row, RowIssue issue) {
        return RowOutcome.failed(row, issue);
    }
}
