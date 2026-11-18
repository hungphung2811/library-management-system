package lms.catalog.catalog.application.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.catalog.catalog.application.ReferenceRules;
import lms.catalog.catalog.domain.CategoryHierarchyException;
import lms.catalog.catalog.domain.InactiveReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.imports.core.ChunkResult;
import lms.imports.core.ImportRow;
import lms.imports.core.RowIssue;
import lms.imports.core.RowOutcome;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi MỘT chunk danh mục trong một giao dịch (bean tách riêng để đi qua proxy giao dịch, ADR-0024 mục 3.6): kiểm lại
 * theo lô với DB hiện tại (một truy vấn cho cả tên và tên cha), dựng bằng domain, ghi bằng persist theo batch. Ném
 * {@link lms.catalog.catalog.domain.ReferenceBatchConflictException} khi trùng khóa do ghi đồng thời (giao dịch
 * rollback, người gọi chạy lại từng dòng).
 */
public class ReferenceChunkWriter {

    private final ReferenceRepository repository;

    public ReferenceChunkWriter(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ChunkResult write(ReferenceKind kind, List<ImportRow> rows) {
        Set<String> keys = new HashSet<>();
        for (ImportRow r : rows) {
            keys.add(ReferenceRows.keyOf(kind, r));
            String parent = ReferenceRows.parentKeyOf(kind, r);
            if (parent != null) keys.add(parent);
        }
        Map<String, Reference> existing = new HashMap<>();
        for (Reference ref : repository.findByNameKeys(kind, keys)) existing.put(ref.nameKey(), ref);

        RowOutcome[] outcomes = new RowOutcome[rows.size()];
        List<Reference> toInsert = new ArrayList<>();
        Map<String, UUID> newRoots = new HashMap<>();
        Set<String> newKeys = new HashSet<>();
        String nameCol = ReferenceRows.nameColumn(kind);
        for (int i = 0; i < rows.size(); i++) {
            ImportRow row = rows.get(i);
            try {
                String key = ReferenceRows.keyOf(kind, row);
                if (existing.containsKey(key) || newKeys.contains(key)) {
                    outcomes[i] = RowOutcome.skipped(row,
                            RowIssue.error(nameCol, ReferenceRows.ALREADY_EXISTS, "Đã tồn tại khi ghi, bỏ qua"));
                    continue;
                }
                UUID parentId = resolveParent(kind, row, existing, newRoots);
                Reference ref = ReferenceRows.toReference(kind, row, parentId);
                toInsert.add(ref);
                newKeys.add(key);
                if (parentId == null) newRoots.put(key, ref.id());
                outcomes[i] = RowOutcome.created(row);
            } catch (RowProblem p) {
                outcomes[i] = new RowOutcome(row.sheet(), row.rowNumber(), lms.imports.core.OutcomeStatus.FAILED,
                        List.of(p.toIssue()));
            }
        }
        repository.insertAll(toInsert);
        return ChunkResult.of(List.of(outcomes));
    }

    /** Cha của thể loại: trong DB (kiểm luật cây bằng {@link ReferenceRules}) hoặc vừa tạo ở chunk này (phải là gốc). */
    private UUID resolveParent(ReferenceKind kind, ImportRow row, Map<String, Reference> existing,
                               Map<String, UUID> newRoots) {
        String parentKey = ReferenceRows.parentKeyOf(kind, row);
        if (parentKey == null) return null;
        Reference parent = existing.get(parentKey);
        if (parent != null) {
            try {
                ReferenceRules.checkParentShape(parent);
            } catch (CategoryHierarchyException e) {
                throw new RowProblem("parentName", e.code(), e.getMessage());
            } catch (InactiveReferenceException e) {
                throw new RowProblem("parentName", ReferenceKind.CATEGORY.inactiveCode(), "Thể loại cha đã ngừng sử dụng");
            }
            return parent.id();
        }
        UUID created = newRoots.get(parentKey);
        if (created != null) return created;
        throw new RowProblem("parentName", ReferenceKind.CATEGORY.notFoundCode(), "Không tìm thấy thể loại cha");
    }
}
