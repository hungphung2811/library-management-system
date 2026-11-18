package lms.customer.shop.client;

import java.util.UUID;
import lms.customer.shop.dto.ShopDtos.ContactRequestBody;
import lms.customer.shop.dto.ShopDtos.OrderCountView;
import lms.customer.shop.dto.ShopDtos.OrderPageView;
import lms.customer.shop.dto.ShopDtos.OrderPaymentView;
import lms.customer.shop.dto.ShopDtos.PaymentOptionsView;
import lms.customer.shop.dto.ShopDtos.OrderView;
import lms.customer.shop.dto.ShopDtos.PlaceOrderRequest;

/**
 * Cổng sang sales {@code /internal/sales/shop/**} (ShopController). Chuyển tiếp TOKEN CỦA KHÁCH (không phải token dịch vụ):
 * sales kiểm module, khóa {@code ShopController/*}, và sở hữu theo {@code userId} trong token. Body gửi đi do client dựng
 * từ record allowlist, không phải thân thô của khách.
 */
public interface ShopClient {

    OrderView placeOrder(String userToken, PlaceOrderRequest request);

    /** @param page tính từ 0; @param size đã chặn trong 1..50 bởi người gọi. */
    OrderPageView myOrders(String userToken, int page, int size);

    /** Số đơn của chính khách ({@code GET /internal/sales/shop/orders/count}). */
    OrderCountView myOrderCounts(String userToken);

    OrderView myOrder(String userToken, UUID orderId);

    /** Thanh toán của đơn ({@code GET /internal/sales/shop/orders/{id}/payment}, ADR-0029 3.1). */
    OrderPaymentView orderPayment(String userToken, UUID orderId);

    /** Phương thức thanh toán khả dụng ({@code GET /internal/sales/shop/payment-options}). */
    PaymentOptionsView paymentOptions(String userToken);

    OrderView cancelMyOrder(String userToken, UUID orderId, String reason);

    /**
     * Gửi liên hệ tới cửa hàng ({@code POST /internal/sales/shop/contact-requests}, token của khách). Thân gửi đi dựng từ
     * record allowlist (6 trường, consent luôn true). Trả khi sales trả 201 (kể cả gửi trùng); lỗi của sales được ném nguyên.
     */
    void submitContactRequest(String userToken, ContactRequestBody request);
}
