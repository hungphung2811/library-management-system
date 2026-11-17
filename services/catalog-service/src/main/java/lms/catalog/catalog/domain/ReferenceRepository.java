package lms.catalog.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu trữ cho cả bốn danh mục chuẩn; {@code kind} chọn bảng. */
public interface ReferenceRepository {

    Reference save(Reference reference);

    Optional<Reference> findById(ReferenceKind kind, UUID id);

    List<Reference> findByIds(ReferenceKind kind, Collection<UUID> ids);

    /** Tên (khóa chữ thường) đã có; {@code excludeId} (có thể null) loại trừ chính mục đang sửa. */
    boolean existsByNameKey(ReferenceKind kind, String nameKey, UUID excludeId);

    /** Thể loại có mục con (bất kể trạng thái). */
    boolean hasChildren(UUID categoryId);

    /** Tìm theo từ khóa trong tên/mô tả, lọc active (null = tất cả) và cha (chỉ thể loại), phân trang từ 0, sắp theo tên. */
    Page search(ReferenceKind kind, String keyword, Boolean active, UUID parentId, int page, int size);

    /** Mục đang active theo tên, tối đa {@code limit}, để đổ ô chọn. */
    List<Reference> options(ReferenceKind kind, String keyword, int limit);

    record Page(List<Reference> items, long total) {
    }
}
