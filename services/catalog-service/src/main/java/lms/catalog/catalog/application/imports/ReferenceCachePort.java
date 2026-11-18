package lms.catalog.catalog.application.imports;

import lms.catalog.catalog.domain.ReferenceKind;

/**
 * Xóa cache danh mục tham chiếu của tenant hiện tại sau khi nhập hàng loạt (ghi qua đường này không đi qua use case
 * tạo/sửa nên không tự xóa cache). Gọi trong {@code TenantContext.runAs} của luồng nền.
 */
public interface ReferenceCachePort {

    void evict(ReferenceKind kind);
}
