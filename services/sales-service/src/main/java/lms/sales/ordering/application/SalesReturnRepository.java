package lms.sales.ordering.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.domain.ReturnPlanner;
import lms.sales.ordering.domain.SalesReturn;

/** Lưu phiếu trả. {@code save}: trùng (tenant, clientRequestId) thì ném DuplicateClientRequestException. */
public interface SalesReturnRepository {

    SalesReturn save(SalesReturn salesReturn);

    Optional<SalesReturn> findById(UUID tenantId, UUID id);

    Optional<SalesReturn> findByClientRequestId(UUID tenantId, UUID clientRequestId);

    List<SalesReturn> findByOrderId(UUID tenantId, UUID orderId);

    /** Đã trả và đã tính hoàn của từng dòng đơn (theo mọi phiếu trả trước đó của đơn). */
    Map<UUID, ReturnPlanner.Prior> priorByOrderLine(UUID tenantId, UUID orderId);
}
