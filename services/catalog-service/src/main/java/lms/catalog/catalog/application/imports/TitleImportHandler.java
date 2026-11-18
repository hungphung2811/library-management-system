package lms.catalog.catalog.application.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.imports.TitleRows.RefLookups;
import lms.catalog.catalog.application.imports.TitleRows.TitleDraft;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.TitleBatchConflictException;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.CopyDraft;
import lms.catalog.inventory.application.CopyWriteConflictException;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssueCodes;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nhập đầu sách (sheet {@code DauSach}) và bản sao (sheet {@code BanSao}) từ một file (ADR-0024 mục 3.4.1, 3.4.2, 5.6).
 * Tham chiếu (tác giả, nhà xuất bản, thể loại, vị trí kệ) chưa có thì báo lỗi dòng, không tự tạo. Dòng trùng khóa
 * (ISBN, mã vạch) có sẵn trong DB là DUPLICATE (bỏ qua). Đầu sách đi cùng bản sao của nó trong cùng chunk/giao dịch.
 */
public class TitleImportHandler implements ImportHandler {

    private static final Logger log = LoggerFactory.getLogger(TitleImportHandler.class);

    private final ImportSchema schema = CatalogImportSchemas.titles();
    private final TitleRepository titles;
    private final WorkRepository works;
    private final ReferenceRepository references;
    private final RegisterCopiesInBatchUseCase copies;
    private final TitleChunkWriter writer;
    private final ImportAuditPort audit;

    public TitleImportHandler(TitleRepository titles, WorkRepository works, ReferenceRepository references,
                              RegisterCopiesInBatchUseCase copies, TitleChunkWriter writer, ImportAuditPort audit) {
        this.works = works;
        this.titles = titles;
        this.references = references;
        this.copies = copies;
        this.writer = writer;
        this.audit = audit;
    }

    @Override
    public ImportSchema schema() {
        return schema;
    }

    // ------------------------------------------------------------------ validate

    @Override
    public List<ImportRow> validate(ImportContext ctx, List<ImportRow> rows) {
        List<ImportRow> out = new ArrayList<>(rows);
        Map<Integer, TitleDraft> drafts = new LinkedHashMap<>();
        List<Integer> copyIdx = new ArrayList<>();

        // 1. Kiểm domain từng dòng đầu sách; gom ISBN/barcode
        Set<String> isbns = new HashSet<>();
        Set<String> invalidIsbns = new HashSet<>();
        for (int i = 0; i < out.size(); i++) {
            ImportRow row = out.get(i);
            if (CatalogImportSchemas.SHEET_COPIES.equals(row.sheet())) {
                copyIdx.add(i);
                continue;
            }
            String rawIsbn = ImportValues.text(row, "isbn");
            if (row.status() != RowStatus.VALID) {
                if (rawIsbn != null) invalidIsbns.add(rawIsbn);
                continue;
            }
            try {
                TitleDraft d = TitleRows.draft(row);
                drafts.put(i, d);
                if (d.title().isbn() != null) isbns.add(d.title().isbn());
            } catch (RowProblem p) {
                out.set(i, row.invalid(p.toIssue()));
                if (rawIsbn != null) invalidIsbns.add(rawIsbn);
            }
        }
        for (int i : copyIdx) {
            ImportRow row = out.get(i);
            String isbn = ImportValues.text(row, "isbn");
            if (row.status() == RowStatus.VALID && isbn != null) isbns.add(isbn);
        }

        // 2. Tra theo lô: ISBN có sẵn (một truy vấn), tham chiếu (một truy vấn mỗi loại)
        Map<String, UUID> dbIsbns = isbns.isEmpty() ? Map.of() : titles.findIdsByIsbns(isbns);
        boolean lending = ctx.hasModule(TitleRows.LENDING);
        Set<String> shelfCodes = new HashSet<>();
        if (lending) {
            for (int i : copyIdx) {
                ImportRow row = out.get(i);
                String code = ImportValues.text(row, "shelfLocationCode");
                if (row.status() == RowStatus.VALID && code != null) shelfCodes.add(code);
            }
        }
        RefLookups refs = RefLookups.load(references, drafts.values(), shelfCodes);

        // 3. Đầu sách: trùng trong file, trùng DB, tham chiếu, cảnh báo thiếu ISBN
        Map<String, RowStatus> fileTitleStatus = new HashMap<>();
        Map<String, Integer> firstByIsbn = new HashMap<>();
        for (Map.Entry<Integer, TitleDraft> e : drafts.entrySet()) {
            int i = e.getKey();
            ImportRow row = out.get(i);
            TitleDraft d = e.getValue();
            String isbn = d.title().isbn();
            if (isbn == null) {
                row = row.withIssue(RowIssue.warning("isbn", "NO_DEDUPE_KEY",
                        "Không có ISBN nên không chống trùng được: nhập lại file sẽ tạo bản trùng"));
            } else {
                Integer first = firstByIsbn.putIfAbsent(isbn, i);
                if (first != null) {
                    out.set(i, row.invalid(RowIssue.error("isbn", IssueCodes.DUPLICATE_IN_FILE,
                            "Trùng ISBN với dòng " + out.get(first).rowNumber() + " trong file")));
                    continue;
                }
            }
            if (isbn != null && dbIsbns.containsKey(isbn)) {
                row = row.duplicate(RowIssue.error("isbn", TitleRows.ALREADY_EXISTS, "ISBN đã tồn tại, sẽ bỏ qua"));
            } else {
                for (RowIssue issue : TitleRows.refIssues(d, refs)) row = row.withIssue(issue);
            }
            out.set(i, row);
            if (isbn != null) fileTitleStatus.put(isbn, row.status());
        }

        // 3b. Cùng tác phẩm với ISBN khác (ADR-0026): gắn vào tác phẩm có sẵn/của dòng khác, hoặc lỗi tham chiếu
        WorkRefResolver.resolve(out, drafts, firstByIsbn, invalidIsbns, refs, titles, works);
        for (Map.Entry<Integer, TitleDraft> e : drafts.entrySet()) {
            String isbn = e.getValue().title().isbn();
            if (isbn != null && firstByIsbn.get(isbn) != null && firstByIsbn.get(isbn).equals(e.getKey())) {
                fileTitleStatus.put(isbn, out.get(e.getKey()).status());
            }
        }

        // 4. Bản sao
        Map<Integer, CopyDraft> copyDrafts = new HashMap<>();
        Set<String> barcodes = new HashSet<>();
        for (int i : copyIdx) {
            ImportRow row = out.get(i);
            if (row.status() != RowStatus.VALID) continue;
            RowIssue problem = copyProblem(row, lending, dbIsbns, fileTitleStatus, invalidIsbns, refs, copyDrafts, i);
            if (problem != null) {
                out.set(i, row.invalid(problem));
            } else {
                barcodes.add(copyDrafts.get(i).barcode());
            }
        }
        Set<String> existingBarcodes = copies.existingBarcodes(barcodes);
        Map<String, Integer> firstByBarcode = new HashMap<>();
        for (int i : copyIdx) {
            ImportRow row = out.get(i);
            if (row.status() != RowStatus.VALID) continue;
            String barcode = copyDrafts.get(i).barcode();
            Integer first = firstByBarcode.putIfAbsent(barcode, i);
            if (first != null) {
                out.set(i, row.invalid(RowIssue.error("barcode", IssueCodes.DUPLICATE_IN_FILE,
                        "Trùng mã vạch với dòng " + out.get(first).rowNumber() + " trong file")));
            } else if (existingBarcodes.contains(barcode)) {
                out.set(i, row.duplicate(RowIssue.error("barcode", TitleRows.ALREADY_EXISTS,
                        "Mã vạch đã tồn tại, sẽ bỏ qua")));
            }
        }
        return out;
    }

    /** Vấn đề nghiệp vụ của một dòng bản sao (không truy vấn DB); null nếu hợp lệ. Đặt bản nháp vào {@code drafts}. */
    private RowIssue copyProblem(ImportRow row, boolean lending, Map<String, UUID> dbIsbns,
                                 Map<String, RowStatus> fileTitleStatus, Set<String> invalidIsbns, RefLookups refs,
                                 Map<Integer, CopyDraft> drafts, int index) {
        if (!lending) {
            return RowIssue.error(null, "MODULE_NOT_ENABLED", "Tenant chưa bật module LENDING nên không nhập được bản sao");
        }
        String isbn = ImportValues.text(row, "isbn");
        if (isbn == null) return RowIssue.error("isbn", IssueCodes.REQUIRED, "Bắt buộc nhập");
        RowStatus fileStatus = fileTitleStatus.get(isbn);
        if (fileStatus == null && invalidIsbns.contains(isbn)) fileStatus = RowStatus.INVALID;
        boolean inDb = dbIsbns.containsKey(isbn);
        if (!inDb) {
            if (fileStatus == null) {
                return RowIssue.error("isbn", "TITLE_NOT_FOUND",
                        "Không có đầu sách ISBN này trong thư viện hay ở sheet " + CatalogImportSchemas.SHEET_TITLES);
            }
            if (fileStatus == RowStatus.INVALID) {
                return RowIssue.error("isbn", "TITLE_ROW_INVALID", "Dòng đầu sách tương ứng không hợp lệ");
            }
        }
        List<RowIssue> shelf = TitleRows.shelfIssues(row, refs);
        if (!shelf.isEmpty()) return shelf.get(0);
        try {
            CopyDraft draft = TitleRows.copyDraft(row, TitleRows.shelfId(row, refs));
            String message = copies.check(draft);
            if (message != null) return RowIssue.error(null, "INVALID_COPY", message);
            drafts.put(index, draft);
            return null;
        } catch (RowProblem p) {
            return p.toIssue();
        }
    }

    // ------------------------------------------------------------------ chunk

    /**
     * Đầu sách đi cùng các bản sao của nó (một nhóm không bị cắt giữa hai chunk, nên chunk có thể lớn hơn
     * {@code chunkSize}); bản sao của đầu sách đã có sẵn nằm ở các chunk riêng sau đó. Xác định theo thứ tự dòng.
     */
    @Override
    public List<List<ImportRow>> planChunks(ImportContext ctx, List<ImportRow> validRows, int chunkSize) {
        Map<String, List<ImportRow>> copiesByIsbn = new LinkedHashMap<>();
        for (ImportRow r : validRows) {
            if (CatalogImportSchemas.SHEET_COPIES.equals(r.sheet())) {
                copiesByIsbn.computeIfAbsent(r.value("isbn").strip(), k -> new ArrayList<>()).add(r);
            }
        }
        List<List<ImportRow>> chunks = new ArrayList<>();
        List<ImportRow> current = new ArrayList<>();
        List<ImportRow> titleRows = new ArrayList<>();
        for (ImportRow r : validRows) if (!CatalogImportSchemas.SHEET_COPIES.equals(r.sheet())) titleRows.add(r);
        // Dòng đích của "cùng tác phẩm với ISBN" ghi trước dòng trỏ tới nó (chunk chạy tuần tự, mỗi chunk một giao dịch)
        for (ImportRow r : WorkRefResolver.orderParentsFirst(titleRows)) {
            current.add(r);
            String isbn = r.value("isbn").strip();
            List<ImportRow> attached = isbn.isEmpty() ? null : copiesByIsbn.remove(isbn);
            if (attached != null) current.addAll(attached);
            if (current.size() >= chunkSize) {
                chunks.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
            current = new ArrayList<>();
        }
        for (List<ImportRow> rest : copiesByIsbn.values()) {
            for (ImportRow r : rest) {
                current.add(r);
                if (current.size() >= chunkSize) {
                    chunks.add(current);
                    current = new ArrayList<>();
                }
            }
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    @Override
    public ChunkResult commitChunk(ImportContext ctx, List<ImportRow> chunk) {
        boolean lending = ctx.hasModule(TitleRows.LENDING);
        TitleChunkWriter.Outcome outcome;
        try {
            outcome = writer.write(chunk, lending);
        } catch (TitleBatchConflictException | CopyWriteConflictException conflict) {
            outcome = groupByGroup(chunk, lending);
        }
        publish(outcome.createdCopies());
        return outcome.result();
    }

    /**
     * Có người vừa tạo cùng ISBN/mã vạch: chunk rollback, chạy lại từng nhóm (đầu sách + bản sao của nó, hoặc một dòng
     * lẻ), mỗi nhóm một giao dịch. Lần chạy lại kiểm lại DB nên dòng trùng thành SKIPPED_DUPLICATE; còn xung đột nữa
     * thì cả nhóm FAILED.
     */
    private TitleChunkWriter.Outcome groupByGroup(List<ImportRow> chunk, boolean lending) {
        Map<String, List<ImportRow>> groups = new LinkedHashMap<>();
        for (ImportRow r : chunk) {
            String isbn = r.value("isbn").strip();
            String key = !isbn.isEmpty() ? "isbn:" + isbn : "row:" + r.sheet() + ":" + r.rowNumber();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        Map<String, RowOutcome> byRow = new HashMap<>();
        List<RegisterCopiesInBatchUseCase.CreatedCopy> created = new ArrayList<>();
        for (List<ImportRow> group : groups.values()) {
            try {
                TitleChunkWriter.Outcome o = writer.write(group, lending);
                for (RowOutcome ro : o.result().outcomes()) byRow.put(ro.sheet() + "#" + ro.rowNumber(), ro);
                created.addAll(o.createdCopies());
            } catch (TitleBatchConflictException | CopyWriteConflictException again) {
                for (ImportRow r : group) {
                    byRow.put(r.sheet() + "#" + r.rowNumber(), RowOutcome.failed(r, RowIssue.error(null, "WRITE_CONFLICT",
                            "Ghi bị xung đột do thay đổi đồng thời, hãy thử lại")));
                }
            }
        }
        List<RowOutcome> ordered = new ArrayList<>(chunk.size());
        for (ImportRow r : chunk) ordered.add(byRow.get(r.sheet() + "#" + r.rowNumber()));
        return new TitleChunkWriter.Outcome(ChunkResult.of(ordered), created);
    }

    /** Sự kiện CopyAvailableEvent phát SAU commit chunk, best-effort (ADR-0001): lỗi chỉ log. */
    private void publish(List<RegisterCopiesInBatchUseCase.CreatedCopy> created) {
        if (created.isEmpty()) return;
        try {
            copies.publishAvailable(created);
        } catch (RuntimeException e) {
            log.warn("Không phát được CopyAvailableEvent sau khi nhập bản sao (best-effort): số bản sao={} lỗi={}",
                    created.size(), e.getClass().getSimpleName());
        }
    }

    @Override
    public void onCompleted(ImportContext ctx, ImportResult result) {
        audit.completed(ctx, result);
    }
}
