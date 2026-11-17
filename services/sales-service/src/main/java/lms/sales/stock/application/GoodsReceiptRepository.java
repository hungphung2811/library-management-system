package lms.sales.stock.application;

import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.ReceiptStatus;

/**
 * {@code save} dùng khóa lạc quan (version); trùng (tenant, clientRequestId) thì ném
 * DuplicateClientRequestException; xung đột version ném SalesException(RECEIPT_STATE_CONFLICT).
 */
public interface GoodsReceiptRepository {

    GoodsReceipt save(GoodsReceipt receipt);

    Optional<GoodsReceipt> findById(UUID tenantId, UUID id);

    Optional<GoodsReceipt> findByClientRequestId(UUID tenantId, UUID clientRequestId);

    PageResult<GoodsReceipt> search(UUID tenantId, ReceiptStatus status, UUID supplierId, int page, int size);
}
