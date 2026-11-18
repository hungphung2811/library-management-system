package lms.integration.orders.client;

import java.util.UUID;
import lms.integration.common.dto.PageView;
import lms.integration.orders.dto.OrderDtos.OrderView;

/** Cổng tới sales-service (đọc đơn). Mọi lời gọi chuyển token integration của người gọi. */
public interface OrderClient {

    /** {@code status} null là mọi trạng thái; đã được kiểm theo tập hợp lệ ở tầng web. */
    PageView<OrderView> list(String status, int page, int size);

    /** {@code 404 NOT_FOUND} khi không có hoặc thuộc tenant khác. */
    OrderView get(UUID id);
}
