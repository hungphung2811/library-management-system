package lms.customer.publicv1.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lms.customer.publicv1.SiteKeyAccess;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.CancelOrderRequest;
import lms.customer.shop.dto.ShopDtos.OrderPageView;
import lms.customer.shop.dto.ShopDtos.OrderView;
import lms.customer.shop.dto.ShopDtos.PaymentMethod;
import lms.customer.shop.dto.ShopDtos.PlaceOrderRequest;
import lms.customer.storefront.Feature;
import lms.customer.storefront.ShopSessionGuard;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đơn hàng của khách ở Public API v1 (Public Site Key, P2), ánh xạ {@code /public/t/{code}/orders}. Bắt buộc phiên khách
 * (JWT kênh STOREFRONT; không có giỏ hàng server, không đặt đơn vãng lai, Q4). Mỗi endpoint: (1) phân giải X-Site-Key (không
 * hợp lệ thì 404 SHOP_NOT_AVAILABLE), (2) {@link ShopSessionGuard}: tenant của token phải khớp tenant của site key, ngược lại 403
 * SHOP_SESSION_MISMATCH, (3) chuyển tiếp TOKEN KHÁCH tới sales (kiểm khóa, module, sở hữu). Body bắt buộc
 * {@code clientRequestId} (UUID) như đường cũ. Mọi phản hồi {@code no-store}.
 */
@RestController
@RequestMapping("/api/public/v1/orders")
public class PublicOrderController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    /** Query gom thành một record (CLAUDE.md). */
    public record OrdersQuery(Integer page, Integer size) {
    }

    private final SiteKeyAccess siteKeys;
    private final ShopSessionGuard guard;
    private final ShopClient shop;

    public PublicOrderController(SiteKeyAccess siteKeys, ShopSessionGuard guard, ShopClient shop) {
        this.siteKeys = siteKeys;
        this.guard = guard;
        this.shop = shop;
    }

    @PostMapping
    public ResponseEntity<OrderView> place(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                           @Valid @RequestBody PlaceOrderRequest request,
                                           Authentication authentication) {
        String token = token(siteKey, authentication);
        if (request.paymentMethod() == PaymentMethod.SEPAY) {
            // ADR-0029 3.3: v1 chưa có endpoint xem QR, nên không cho tạo đơn chờ thanh toán
            throw new PublicApiException(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_ENABLED",
                    "Phương thức thanh toán này hiện không khả dụng");
        }
        OrderView order = shop.placeOrder(token, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/public/v1/orders/" + order.id()))
                .cacheControl(CacheControl.noStore()).body(order);
    }

    @GetMapping
    public ResponseEntity<OrderPageView> mine(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                              OrdersQuery query, Authentication authentication) {
        String token = token(siteKey, authentication);
        int page = query.page() == null || query.page() < 0 ? 0 : query.page();
        int size = query.size() == null || query.size() < 1 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        return PublicCache.noStore(shop.myOrders(token, page, size));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderView> get(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                         @PathVariable String orderId, Authentication authentication) {
        String token = token(siteKey, authentication);
        return PublicCache.noStore(shop.myOrder(token, orderId(orderId)));
    }

    @PostMapping("/{orderId}/cancellation")
    public ResponseEntity<OrderView> cancel(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                            @PathVariable String orderId,
                                            @RequestBody(required = false) @Valid CancelOrderRequest request,
                                            Authentication authentication) {
        String token = token(siteKey, authentication);
        UUID id = orderId(orderId);
        return PublicCache.noStore(shop.cancelMyOrder(token, id, request == null ? null : request.reason()));
    }

    /** Phân giải site key rồi kiểm phiên; trả access token của khách để chuyển tiếp. */
    private String token(String siteKey, Authentication authentication) {
        StorefrontAccess access = siteKeys.resolve(siteKey, Feature.SHOP);
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
