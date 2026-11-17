package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.CategoryHierarchyException;
import lms.catalog.catalog.domain.InactiveReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Kiểm chung cho tạo/sửa danh mục: cây thể loại tối đa 2 cấp, không vòng cha-con. */
final class ReferenceRules {

    private ReferenceRules() {
    }

    /**
     * @param selfId id mục đang sửa (null khi tạo mới)
     * @param currentParentId cha hiện tại của mục (null khi tạo mới); giữ nguyên cha cũ thì không bắt buộc cha còn active
     */
    static void checkParent(ReferenceRepository repository, UUID selfId, UUID parentId, UUID currentParentId) {
        if (parentId == null) return;
        if (parentId.equals(selfId)) {
            throw new CategoryHierarchyException("CATEGORY_CYCLE", "Thể loại không thể là cha của chính nó");
        }
        Reference parent = repository.findById(ReferenceKind.CATEGORY, parentId)
                .orElseThrow(() -> new ReferenceNotFoundException(ReferenceKind.CATEGORY, parentId));
        // Đi ngược lên chuỗi cha để bắt vòng (với giới hạn 2 cấp thì chỉ xảy ra khi dữ liệu đã hỏng, nhưng vẫn chặn).
        Set<UUID> seen = new HashSet<>();
        Reference cursor = parent;
        while (cursor != null) {
            if (selfId != null && cursor.id().equals(selfId)) {
                throw new CategoryHierarchyException("CATEGORY_CYCLE", "Không thể tạo vòng cha-con giữa các thể loại");
            }
            if (!seen.add(cursor.id()) || cursor.parentId() == null) break;
            UUID next = cursor.parentId();
            cursor = repository.findById(ReferenceKind.CATEGORY, next).orElse(null);
        }
        if (parent.parentId() != null) {
            throw new CategoryHierarchyException("CATEGORY_TOO_DEEP", "Cây thể loại chỉ tối đa 2 cấp");
        }
        if (selfId != null && repository.hasChildren(selfId)) {
            throw new CategoryHierarchyException("CATEGORY_TOO_DEEP",
                    "Thể loại đang có thể loại con nên không thể trở thành thể loại con (cây tối đa 2 cấp)");
        }
        if (!parent.active() && !parentId.equals(currentParentId)) {
            throw new InactiveReferenceException(ReferenceKind.CATEGORY, parentId);
        }
    }
}
