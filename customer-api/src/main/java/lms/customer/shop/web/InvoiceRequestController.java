package lms.customer.shop.web;

import jakarta.validation.Valid;
import java.util.UUID;
import lms.customer.shop.client.ShopInvoiceClient;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestBody;
import lms.customer.shop.dto.ShopInvoiceDtos.InvoiceRequestStatusView;
import lms.customer.storefront.Feature;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yêu cầu xuất hóa đơn cho đơn của chính khách (ADR-0030 gói A, D2). Như {@link OrderController}: (1) phân giải {code},
 * (2) {@link ShopSessionGuard}, (3) chuyển tiếp TOKEN NGƯỜI DÙNG tới sales, nơi kiểm khóa, module và SỞ HỮU ĐƠN (đơn của
 * người khác, đơn POS, đơn không tồn tại đều 404 ORDER_NOT_FOUND cùng một thân). Thân là record allowlist, phản hồi chỉ có
 * trạng thái, mọi phản hồi {@code no-store}.
 */
@RestController
@RequestMapping("/public/t/{code}/orders/{orderId}/invoice-request")
public class InvoiceRequestController {

    private final StorefrontAccessCache storefronts;
    private final ShopSessionGuard guard;
    private final ShopInvoiceClient invoices;

    public InvoiceRequestController(StorefrontAccessCache storefronts, ShopSessionGuard guard,
                                    ShopInvoiceClient invoices) {
        this.storefronts = storefronts;
        this.guard = guard;
        this.invoices = invoices;
    }

    @PutMapping
    public ResponseEntity<InvoiceRequestStatusView> save(@PathVariable String code, @PathVariable String orderId,
                                                         @Valid @RequestBody InvoiceRequestBody request,
                                                         Authentication authentication) {
        StorefrontAccess access = storefronts.resolve(code).require(Feature.SHOP);
        String token = guard.requireSession(access, authentication).getTokenValue();
        return PublicCache.noStore(invoices.saveInvoiceRequest(token, orderId(orderId), request));
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
