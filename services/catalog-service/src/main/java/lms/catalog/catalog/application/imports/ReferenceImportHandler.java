package lms.catalog.catalog.application.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lms.catalog.catalog.application.ReferenceRules;
import lms.catalog.catalog.domain.CategoryHierarchyException;
import lms.catalog.catalog.domain.InactiveReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceBatchConflictException;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportHandler;
import lms.imports.core.ImportResult;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;
import lms.imports.core.IssueCodes;
import lms.imports.core.OutcomeStatus;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import lms.imports.core.RowStatus;

/**
 * Nhập hàng loạt một loại danh mục chuẩn (tác giả, nhà xuất bản, thể loại, vị trí kệ; ADR-0024 mục 3.4.3). Một lớp cho
 * cả bốn loại, khác nhau ở {@link ReferenceKind} và schema. Kiểm bằng domain ({@link ReferenceRows}), kiểm trùng
 * bằng MỘT truy vấn cho cả file, ghi theo chunk qua {@link ReferenceChunkWriter}. Thể loại: cha ghi trước con.
 */
public class ReferenceImportHandler implements ImportHandler {

    private final ReferenceKind kind;
    private final ImportSchema schema;
    private final ReferenceRepository repository;
    private final ReferenceChunkWriter writer;
    private final ReferenceCachePort cache;
    private final ImportAuditPort audit;

    public ReferenceImportHandler(ReferenceKind kind, ImportSchema schema, ReferenceRepository repository,
                                  ReferenceChunkWriter writer, ReferenceCachePort cache, ImportAuditPort audit) {
        this.kind = kind;
        this.schema = schema;
        this.repository = repository;
        this.writer = writer;
        this.cache = cache;
        this.audit = audit;
    }

    @Override
    public ImportSchema schema() {
        return schema;
    }

    @Override
    public List<ImportRow> validate(ImportContext ctx, List<ImportRow> rows) {
        String nameCol = ReferenceRows.nameColumn(kind);
        List<ImportRow> out = new ArrayList<>(rows);

        // 1. Kiểm bằng domain và trùng trong file
        Map<String, Integer> firstByKey = new HashMap<>();
        for (int i = 0; i < out.size(); i++) {
            ImportRow row = out.get(i);
            if (row.status() != RowStatus.VALID) continue;
            try {
                ReferenceRows.toReference(kind, row, null);
            } catch (RowProblem p) {
                out.set(i, row.invalid(p.toIssue()));
                continue;
            }
            String key = ReferenceRows.keyOf(kind, row);
            Integer first = firstByKey.putIfAbsent(key, i);
            if (first != null) {
                out.set(i, row.invalid(RowIssue.error(nameCol, IssueCodes.DUPLICATE_IN_FILE,
                        "Trùng với dòng " + out.get(first).rowNumber() + " trong file")));
            }
        }

        // 2. Một truy vấn cho cả khóa tên và khóa cha
        Set<String> keys = new HashSet<>(firstByKey.keySet());
        for (ImportRow row : out) {
            if (row.status() != RowStatus.VALID) continue;
            String parentKey = ReferenceRows.parentKeyOf(kind, row);
            if (parentKey != null) keys.add(parentKey);
        }
        Map<String, Reference> existing = new HashMap<>();
        if (!keys.isEmpty()) {
            for (Reference ref : repository.findByNameKeys(kind, keys)) existing.put(ref.nameKey(), ref);
        }

        // 3. Trùng với DB
        for (int i = 0; i < out.size(); i++) {
            ImportRow row = out.get(i);
            if (row.status() != RowStatus.VALID) continue;
            if (existing.containsKey(ReferenceRows.keyOf(kind, row))) {
                out.set(i, row.duplicate(RowIssue.error(nameCol, ReferenceRows.ALREADY_EXISTS,
                        "Đã tồn tại trong thư viện, sẽ bỏ qua")));
            }
        }

        // 4. Thể loại cha: trong DB hoặc là dòng gốc hợp lệ của cùng file
        if (kind == ReferenceKind.CATEGORY) {
            for (int i = 0; i < out.size(); i++) {
                ImportRow row = out.get(i);
                if (row.status() != RowStatus.VALID) continue;
                String parentKey = ReferenceRows.parentKeyOf(kind, row);
                if (parentKey == null) continue;
                RowIssue problem = parentProblem(row, parentKey, existing, firstByKey, out);
                if (problem != null) out.set(i, row.invalid(problem));
            }
        }
        return out;
    }

    private RowIssue parentProblem(ImportRow row, String parentKey, Map<String, Reference> existing,
                                   Map<String, Integer> firstByKey, List<ImportRow> out) {
        if (parentKey.equals(ReferenceRows.keyOf(kind, row))) {
            return RowIssue.error("parentName", "CATEGORY_CYCLE", "Thể loại không thể là cha của chính nó");
        }
        Reference dbParent = existing.get(parentKey);
        if (dbParent != null) {
            try {
                ReferenceRules.checkParentShape(dbParent);
                return null;
            } catch (CategoryHierarchyException e) {
                return RowIssue.error("parentName", e.code(), e.getMessage());
            } catch (InactiveReferenceException e) {
                return RowIssue.error("parentName", kind.inactiveCode(), "Thể loại cha đã ngừng sử dụng");
            }
        }
        Integer idx = firstByKey.get(parentKey);
        if (idx == null) {
            return RowIssue.error("parentName", kind.notFoundCode(), "Không tìm thấy thể loại cha");
        }
        ImportRow parentRow = out.get(idx);
        if (parentRow.status() != RowStatus.VALID) {
            return RowIssue.error("parentName", kind.notFoundCode(),
                    "Thể loại cha ở dòng " + parentRow.rowNumber() + " không hợp lệ");
        }
        if (ReferenceRows.parentKeyOf(kind, parentRow) != null) {
            return RowIssue.error("parentName", "CATEGORY_TOO_DEEP", "Cây thể loại chỉ tối đa 2 cấp");
        }
        return null;
    }

    /** Thể loại: dòng gốc (không có cha) trước dòng con để cha có trước khi ghi con; loại khác chia đều. */
    @Override
    public List<List<ImportRow>> planChunks(ImportContext ctx, List<ImportRow> validRows, int chunkSize) {
        if (kind != ReferenceKind.CATEGORY) return ImportHandler.super.planChunks(ctx, validRows, chunkSize);
        List<ImportRow> ordered = new ArrayList<>(validRows.size());
        for (ImportRow r : validRows) if (ReferenceRows.parentKeyOf(kind, r) == null) ordered.add(r);
        for (ImportRow r : validRows) if (ReferenceRows.parentKeyOf(kind, r) != null) ordered.add(r);
        return ImportHandler.super.planChunks(ctx, ordered, chunkSize);
    }

    @Override
    public ChunkResult commitChunk(ImportContext ctx, List<ImportRow> chunk) {
        ChunkResult result;
        try {
            result = writer.write(kind, chunk);
        } catch (ReferenceBatchConflictException conflict) {
            result = rowByRow(chunk);
        }
        if (result.count(OutcomeStatus.CREATED) > 0) {
            evictQuietly();
        }
        return result;
    }

    /**
     * Người khác vừa tạo cùng tên: chunk rollback, chạy lại từng dòng (mỗi dòng một giao dịch). Lần chạy lại kiểm lại
     * DB nên dòng trùng thành SKIPPED_DUPLICATE; xung đột còn lại sau đó mới là FAILED.
     */
    private ChunkResult rowByRow(List<ImportRow> chunk) {
        List<RowOutcome> outcomes = new ArrayList<>();
        for (ImportRow row : chunk) {
            try {
                outcomes.addAll(writer.write(kind, List.of(row)).outcomes());
            } catch (ReferenceBatchConflictException again) {
                outcomes.add(RowOutcome.failed(row, RowIssue.error(ReferenceRows.nameColumn(kind), "WRITE_CONFLICT",
                        "Ghi bị xung đột do thay đổi đồng thời, hãy thử lại")));
            }
        }
        return ChunkResult.of(outcomes);
    }

    @Override
    public void onCompleted(ImportContext ctx, ImportResult result) {
        evictQuietly();
        audit.completed(ctx, result);
    }

    private void evictQuietly() {
        try {
            cache.evict(kind);
        } catch (RuntimeException ignored) {
            // cache chỉ là phụ (fail-open): hết TTL 60 giây sẽ tự mới
        }
    }
}
