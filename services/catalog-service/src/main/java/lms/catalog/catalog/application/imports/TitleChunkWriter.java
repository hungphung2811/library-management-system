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
import lms.catalog.catalog.domain.ReferenceRepository;
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

    public TitleChunkWriter(TitleRepository titles, ReferenceRepository references, RegisterCopiesInBatchUseCase copies) {
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
            Title t = TitleRows.classified(d, refs);
            toInsert.add(t);
            if (isbn != null) newIds.put(isbn, t.id());
            outcomes[i] = RowOutcome.created(row);
        }
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

    private static RowOutcome failed(ImportRow row, RowIssue issue) {
        return RowOutcome.failed(row, issue);
    }
}
