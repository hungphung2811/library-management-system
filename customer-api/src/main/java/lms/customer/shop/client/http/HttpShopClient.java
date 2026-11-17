package lms.customer.shop.client.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.ContactRequestBody;
import lms.customer.shop.dto.ShopDtos.OrderLineView;
import lms.customer.shop.dto.ShopDtos.OrderPageView;
import lms.customer.shop.dto.ShopDtos.OrderView;
import lms.customer.shop.dto.ShopDtos.PlaceOrderRequest;
import lms.customer.shop.dto.ShopDtos.RecipientView;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Gọi {@code /internal/sales/shop/**} của sales-service (docs/api/sales.md, "Endpoint khách"). Thân gửi đi dựng từ record
 * allowlist; phản hồi của sales được đọc vào record riêng ({@code Upstream*}) rồi ÁNH XẠ TỪNG TRƯỜNG sang DTO công khai,
 * nên trường nội bộ nào sales thêm sau này cũng không lọt ra ngoài. Lỗi của sales (409 INSUFFICIENT_STOCK, 404
 * ORDER_NOT_FOUND, 403 FORBIDDEN...) được ném nguyên để PublicExceptionHandler lọc theo danh sách cho phép.
 */
public class HttpShopClient implements ShopClient {

    /** Trạng thái duy nhất khách được hủy (sales kiểm lại; chỉ để FE biết có hiện nút hủy). */
    static final String CANCELLABLE_STATUS = "PENDING_CONFIRMATION";

    private final RestClient client;

    public HttpShopClient(RestClient client) {
        this.client = client;
    }

    @Override
    public OrderView placeOrder(String userToken, PlaceOrderRequest request) {
        UpstreamOrder order = client.post().uri("/internal/sales/shop/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .body(PlaceBody.from(request)).retrieve().body(UpstreamOrder.class);
        return view(order);
    }

    @Override
    public OrderPageView myOrders(String userToken, int page, int size) {
        UpstreamPage body = client.get()
                .uri(b -> b.path("/internal/sales/shop/orders").queryParam("page", page).queryParam("size", size).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve().body(UpstreamPage.class);
        if (body == null) {
            throw PublicApiException.serviceUnavailable();
        }
        List<UpstreamOrder> items = body.items() == null ? List.of() : body.items();
        return new OrderPageView(items.stream().map(HttpShopClient::view).toList(), body.page(), body.size(), body.total());
    }

    @Override
    public OrderView myOrder(String userToken, UUID orderId) {
        return view(client.get().uri("/internal/sales/shop/orders/{id}", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken).retrieve().body(UpstreamOrder.class));
    }

    @Override
    public OrderView cancelMyOrder(String userToken, UUID orderId, String reason) {
        return view(client.post().uri("/internal/sales/shop/orders/{id}/cancellation", orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .body(new CancelBody(reason)).retrieve().body(UpstreamOrder.class));
    }

    @Override
    public void submitContactRequest(String userToken, ContactRequestBody request) {
        client.post().uri("/internal/sales/shop/contact-requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .body(ContactBody.from(request)).retrieve().toBodilessEntity();
    }

    static OrderView view(UpstreamOrder o) {
        if (o == null || o.id() == null) {
            throw PublicApiException.serviceUnavailable();
        }
        UpstreamRecipient r = o.recipient();
        List<UpstreamLine> lines = o.lines() == null ? List.of() : o.lines();
        return new OrderView(o.id(), o.orderNo(), o.status(), o.paymentStatus(), o.paymentMethod(), o.fulfilment(),
                r == null ? null : new RecipientView(r.name(), r.phone(), r.address()), o.currency(), o.subtotal(),
                o.discountTotal(), o.vatTotal(), o.shippingFee(), o.grandTotal(), o.createdAt(), o.confirmedAt(),
                o.paidAt(), o.completedAt(), o.cancelledAt(),
                lines.stream().map(l -> new OrderLineView(l.titleId(), l.titleName(), l.quantity(), l.unitPrice(),
                        l.lineTotal())).toList(),
                CANCELLABLE_STATUS.equals(o.status()));
    }

    // ---------- Thân gửi sales (allowlist) ----------

    record PlaceLine(UUID titleId, int quantity) {
    }

    record PlaceRecipient(String name, String phone, String address) {
        @Override
        public String toString() {
            return "PlaceRecipient[***]";
        }
    }

    record PlaceBody(List<PlaceLine> lines, String fulfilment, String paymentMethod, PlaceRecipient recipient,
                     String clientRequestId) {

        static PlaceBody from(PlaceOrderRequest r) {
            return new PlaceBody(
                    r.lines().stream().map(l -> new PlaceLine(l.titleId(), l.quantity())).toList(),
                    r.fulfilment().name(), r.paymentMethod().name(),
                    new PlaceRecipient(r.recipient().name().trim(), r.recipient().phone().trim(),
                            r.recipient().address() == null || r.recipient().address().isBlank() ? null
                                    : r.recipient().address().trim()),
                    r.clientRequestId().toString());
        }
    }

    /** Đúng 6 trường allowlist gửi sales; consent luôn true vì BFF đã từ chối khi khác true. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ContactBody(String topic, String fullName, String email, String phone, String message, boolean consent) {

        static ContactBody from(ContactRequestBody r) {
            return new ContactBody(r.topic().name(), r.fullName().trim(), r.email().trim(),
                    r.phone() == null || r.phone().isBlank() ? null : r.phone().trim(), r.message().trim(), true);
        }

        @Override
        public String toString() {
            return "ContactBody[***]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CancelBody(String reason) {
    }

    // ---------- Phản hồi của sales (chỉ đọc các trường này) ----------

    record UpstreamRecipient(String name, String phone, String address) {
    }

    record UpstreamLine(UUID titleId, String titleName, int quantity, long unitPrice, long lineTotal) {
    }

    record UpstreamOrder(UUID id, String orderNo, String status, String paymentStatus, String paymentMethod,
                         String fulfilment, UpstreamRecipient recipient, String currency, long subtotal,
                         long discountTotal, long vatTotal, long shippingFee, long grandTotal, Instant createdAt,
                         Instant confirmedAt, Instant paidAt, Instant completedAt, Instant cancelledAt,
                         List<UpstreamLine> lines) {
    }

    record UpstreamPage(List<UpstreamOrder> items, long total, int page, int size) {
    }
}
