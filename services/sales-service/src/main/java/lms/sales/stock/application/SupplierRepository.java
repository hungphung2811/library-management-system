package lms.sales.stock.application;

import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.stock.domain.Supplier;

public interface SupplierRepository {

    Supplier save(Supplier supplier);

    Optional<Supplier> findById(UUID tenantId, UUID id);

    /** So khớp không phân biệt hoa thường; excludeId (nếu có) bị bỏ qua khi kiểm trùng lúc cập nhật. */
    boolean existsByName(UUID tenantId, String name, UUID excludeId);

    PageResult<Supplier> search(UUID tenantId, String keyword, int page, int size);

    /**
     * Nhập Excel (ADR-0024): tập tên (chữ thường) đã tồn tại trong số {@code lowerNames}, MỘT truy vấn mỗi 1.000 tên.
     * Mặc định qua {@link #existsByName} cho bản giả test.
     */
    default java.util.Set<String> existingLowerNames(UUID tenantId, java.util.Collection<String> lowerNames) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String n : lowerNames) {
            if (existsByName(tenantId, n, null)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Nhập Excel: chèn nhiều nhà cung cấp mới (persist không SELECT), flush một lần. Trùng tên thì ném
     * SalesException(SUPPLIER_EXISTS). Mặc định lưu từng cái.
     */
    default void insertAll(java.util.List<Supplier> suppliers) {
        suppliers.forEach(this::save);
    }
}
