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
}
