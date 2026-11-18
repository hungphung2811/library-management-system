package lms.customer.shop.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.CancelOrderRequest;
import lms.customer.shop.dto.ShopDtos.OrderCountView;
import lms.customer.shop.dto.ShopDtos.OrderPageView;
import lms.customer.shop.dto.ShopDtos.OrderPaymentView;
import lms.customer.shop.dto.ShopDtos.OrderView;
import lms.customer.shop.dto.ShopDtos.PlaceOrderRequest;
import lms.customer.storefront.Feature;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đơn hàng của khách đã đăng nhập (ADR-0011 2.4, 3.3). Mỗi endpoint: (1) phân giải {code} (không khả dụng thì 404
 * SHOP_NOT_AVAILABLE), (2) {@link ShopSessionGuard}: token không phải token dịch vụ và tenantId khớp {code}, ngược lại 403
 * SHOP_SESSION_MISMATCH, (3) chuyển tiếp TOKEN NGƯỜI DÙNG tới sales, nơi kiểm khóa, module và sở hữu. Thân request là
 * record allowlist, phản hồi là record riêng; mọi phản hồi {@code no-store}.
 */
@RestController
@RequestMapping("/public/t/{code}/orders")
public class OrderController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    /** Query gom thành một record (CLAUDE.md). */
    public record OrdersQuery(Integer page, Integer size) {
    }

    private final StorefrontAccessCache storefronts;
    private final ShopSessionGuard guard;
    private final ShopClient shop;

    public OrderController(StorefrontAccessCache storefronts, ShopSessionGuard guard, ShopClient shop) {
        this.storefronts = storefronts;
        this.guard = guard;
        this.shop = shop;
    }

    @PostMapping
    public ResponseEntity<OrderView> place(@PathVariable String code, @Valid @RequestBody PlaceOrderRequest request,
                                           Authentication authentication) {
        String token = token(code, authentication);
        OrderView order = shop.placeOrder(token, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/public/t/" + storefronts.resolve(code).code() + "/orders/" + order.id()))
                .cacheControl(CacheControl.noStore()).body(order);
    }

    @GetMapping
    public ResponseEntity<OrderPageView> mine(@PathVariable String code, OrdersQuery query,
                                              Authentication authentication) {
        String token = token(code, authentication);
        int page = query.page() == null || query.page() < 0 ? 0 : query.page();
        int size = query.size() == null || query.size() < 1 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        return PublicCache.noStore(shop.myOrders(token, page, size));
    }

    /** Số đơn của khách; khai báo trước {orderId} (Spring cũng ưu tiên đường dẫn literal). */
    @GetMapping("/count")
    public ResponseEntity<OrderCountView> count(@PathVariable String code, Authentication authentication) {
        String token = token(code, authentication);
        return PublicCache.noStore(shop.myOrderCounts(token));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderView> get(@PathVariable String code, @PathVariable String orderId,
                                         Authentication authentication) {
        String token = token(code, authentication);
        return PublicCache.noStore(shop.myOrder(token, orderId(orderId)));
    }

    /** Mã QR và trạng thái thanh toán của đơn (ADR-0029 3.3); FE hỏi lại theo chu kỳ nên no-store. */
    @GetMapping("/{orderId}/payment")
    public ResponseEntity<OrderPaymentView> payment(@PathVariable String code, @PathVariable String orderId,
                                                    Authentication authentication) {
        String token = token(code, authentication);
        return PublicCache.noStore(shop.orderPayment(token, orderId(orderId)));
    }

    @PostMapping("/{orderId}/cancellation")
    public ResponseEntity<OrderView> cancel(@PathVariable String code, @PathVariable String orderId,
                                            @RequestBody(required = false) @Valid CancelOrderRequest request,
                                            Authentication authentication) {
        String token = token(code, authentication);
        UUID id = orderId(orderId);
        return PublicCache.noStore(shop.cancelMyOrder(token, id, request == null ? null : request.reason()));
    }

    /** Phân giải storefront rồi kiểm phiên; trả access token của khách để chuyển tiếp. */
    private String token(String code, Authentication authentication) {
        StorefrontAccess access = storefronts.resolve(code).require(Feature.SHOP);
        return guard.requireSession(access, authentication).getTokenValue();
    }

    /** orderId không phải UUID thì không thể tồn tại: 404 ORDER_NOT_FOUND (cùng thân), không gọi sales. */
    private static UUID orderId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new PublicApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Không tìm thấy đơn hàng");
        }
    }
}
