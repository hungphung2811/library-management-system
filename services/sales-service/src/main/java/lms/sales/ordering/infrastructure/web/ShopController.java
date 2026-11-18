package lms.sales.ordering.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.cache.RateLimiter;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khách đã đăng nhập đặt, xem, hủy đơn của chính mình (role Khách hàng được gán khóa ShopController/*). {@code userId}
 * lấy từ token, không nhận từ body/query; đơn của người khác trả 404 ORDER_NOT_FOUND. Danh mục sách đang bán đọc
 * qua StorefrontController (công khai), không có ShopController/listItems.
 */
@RestController
@RequestMapping("/internal/sales/shop")
@RequiresModule(TenantModule.SALES)
public class ShopController {

    public record LineRequest(@NotNull UUID titleId, @NotNull @Min(1) Integer quantity) {
    }

    public record RecipientRequest(@NotBlank @Size(max = 255) String name, @NotBlank @Size(max = 30) String phone,
                                   @Size(max = 500) String address) {
        @Override
        public String toString() { // dữ liệu cá nhân: không in
            return "RecipientRequest[***]";
        }
    }

    public record PlaceOrderRequest(@NotEmpty @Size(max = 200) List<@Valid @NotNull LineRequest> lines,
                                    @NotNull Fulfilment fulfilment, @NotNull PaymentMethod paymentMethod,
                                    @NotNull @Valid RecipientRequest recipient,
                                    @NotBlank @Size(max = 100) String clientRequestId,
                                    @Size(max = 500) String customerNote) {
        @Override
        public String toString() { // ghi chú của khách có thể chứa dữ liệu cá nhân: không in
            return "PlaceOrderRequest[lines=" + (lines == null ? 0 : lines.size()) + "]";
        }
    }

    public record MyOrdersQuery(Integer page, Integer size) {
    }

    public record CancelMyOrderRequest(@Size(max = 500) String reason) {
    }

    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final PlaceOnlineOrderUseCase placeOrder;
    private final ShopOrderUseCases shopOrders;
    private final RateLimiter rateLimiter;
    private final OrderAbusePolicyUseCases policies;

    public ShopController(PlaceOnlineOrderUseCase placeOrder, ShopOrderUseCases shopOrders, RateLimiter rateLimiter,
                          OrderAbusePolicyUseCases policies) {
        this.placeOrder = placeOrder;
        this.shopOrders = shopOrders;
        this.rateLimiter = rateLimiter;
        this.policies = policies;
    }

    @PostMapping("/orders")
    public ResponseEntity<ShopOrderView> placeOrder(@Valid @RequestBody PlaceOrderRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        enforceRateLimit(RequestSupport.tenant(), RequestSupport.user(jwt));
        var order = placeOrder.place(new PlaceOnlineOrderCommand(RequestSupport.tenant(), RequestSupport.user(jwt),
                request.lines().stream().map(l -> new PlaceOnlineOrderCommand.Line(l.titleId(), l.quantity()))
                        .toList(), request.fulfilment(), request.paymentMethod(),
                new Recipient(request.recipient().name(), request.recipient().phone(),
                        request.recipient().address()), RequestSupport.strictClientRequestId(request.clientRequestId()),
                request.customerNote()));
        return ResponseEntity.created(URI.create("/internal/sales/shop/orders/" + order.order().id()))
                .body(ShopOrderView.from(order));
    }

    /**
     * Giới hạn tốc độ đặt đơn theo khách rồi theo cả tenant (Redis, khóa theo tenant nên không ảnh hưởng tenant khác);
     * ngưỡng lấy từ chính sách của tenant. Kiểm theo khách trước để khách spam không ăn hạn mức chung của tenant.
     */
    private void enforceRateLimit(UUID tenantId, UUID userId) {
        var policy = policies.current(tenantId);
        if (!rateLimiter.tryAcquire("order.create.user", userId.toString(), policy.ordersPerMinutePerUser(),
                RATE_WINDOW)
                || !rateLimiter.tryAcquire("order.create.tenant", "tenant", policy.ordersPerMinuteTenant(),
                RATE_WINDOW)) {
            throw new SalesException(ErrorCode.ORDER_RATE_LIMITED, "Đặt đơn quá nhanh, vui lòng thử lại sau");
        }
    }

    @GetMapping("/orders")
    public PageResult<ShopOrderView> myOrders(MyOrdersQuery query, @AuthenticationPrincipal Jwt jwt) {
        return shopOrders.myOrders(RequestSupport.tenant(), RequestSupport.user(jwt),
                RequestSupport.page(query.page()), RequestSupport.shopSize(query.size())).map(ShopOrderView::from);
    }

    /**
     * Số đơn của chính mình (huy hiệu trên header). Chỉ trả số của người trong token nên chỉ cần đăng nhập, không có khóa
     * riêng (tránh phải gán thêm khóa cho role Khách hàng và allowlist token storefront). Khai báo trước {id}; Spring
     * cũng ưu tiên đường dẫn literal.
     */
    @AuthenticatedOnly
    @GetMapping("/orders/count")
    public ShopOrderUseCases.OrderCounts myOrderCounts(@AuthenticationPrincipal Jwt jwt) {
        return shopOrders.myOrderCounts(RequestSupport.tenant(), RequestSupport.user(jwt));
    }

    @GetMapping("/orders/{id}")
    public ShopOrderView myOrder(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ShopOrderView.from(shopOrders.myOrder(RequestSupport.tenant(), RequestSupport.user(jwt), id));
    }

    @PostMapping("/orders/{id}/cancellation")
    public ShopOrderView cancelMyOrder(@PathVariable UUID id,
                                   @RequestBody(required = false) @Valid CancelMyOrderRequest body,
                                   @AuthenticationPrincipal Jwt jwt) {
        return ShopOrderView.from(shopOrders.cancelMyOrder(RequestSupport.tenant(), RequestSupport.user(jwt), id,
                body == null ? null : body.reason()));
    }
}
