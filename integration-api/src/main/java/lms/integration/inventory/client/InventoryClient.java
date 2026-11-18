package lms.integration.inventory.client;

import java.util.UUID;
import lms.integration.common.dto.PageView;
import lms.integration.inventory.dto.InventoryDtos.InventoryItemView;

/** Cổng tới sales-service (tồn kho). Mọi lời gọi chuyển token integration của người gọi. */
public interface InventoryClient {

    PageView<InventoryItemView> list(boolean lowStockOnly, int page, int size);

    /** {@code 404 NOT_FOUND} khi mặt hàng chưa được đưa vào bán hoặc thuộc tenant khác. */
    InventoryItemView get(UUID titleId);
}
