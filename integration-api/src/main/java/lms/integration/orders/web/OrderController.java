package lms.integration.orders.web;

import java.util.UUID;
import lms.integration.common.PageParams;
import lms.integration.common.dto.PageView;
import lms.integration.orders.client.OrderClient;
import lms.integration.orders.dto.OrderDtos;
import lms.integration.orders.dto.OrderDtos.OrderView;
import lms.integration.web.IntegrationApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đơn hàng (scope {@code orders:read}; tenant phải bật module SALES, do sales-service kiểm). Chỉ đọc, không có thông tin
 * liên hệ của khách: không có endpoint contact/notes/receipt.
 */
@RestController
@RequestMapping("/api/integration/v1/orders")
public class OrderController {

    /** status là một trong {@link OrderDtos#ORDER_STATUSES} (không phân biệt hoa thường); page từ 0; size 1..100. */
    public record OrderQuery(String status, Integer page, Integer size) {
    }

    private final OrderClient orders;

    public OrderController(OrderClient orders) {
        this.orders = orders;
    }

    @GetMapping
    public ResponseEntity<PageView<OrderView>> list(OrderQuery query) {
        String status = null;
        if (query.status() != null && !query.status().isBlank()) {
            status = query.status().trim().toUpperCase(java.util.Locale.ROOT);
            if (!OrderDtos.ORDER_STATUSES.contains(status)) {
                throw new IntegrationApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "status không hợp lệ");
            }
        }
        return noStore(orders.list(status, PageParams.page(query.page()), PageParams.size(query.size())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderView> get(@PathVariable UUID id) {
        return noStore(orders.get(id));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
