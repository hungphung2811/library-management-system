package lms.customer.shop.dto;

import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.customer.web.NoControlChars;
import lms.customer.web.StrictBooleanDeserializer;

/**
 * DTO công khai của đơn hàng khách (ADR-0011 3.3). Request là record ALLOWLIST do customer-api định nghĩa (KHÔNG chuyển
 * tiếp thân thô): không có giá, giảm giá, phí ship, trạng thái, userId, tenant, kênh; trường lạ trong JSON bị bỏ qua.
 * Response là record riêng ánh xạ từ {@code ShopOrderView} của sales, không dùng DTO/entity nội bộ.
 */
public final class ShopDtos {

    private ShopDtos() {
    }

    /** Hình thức nhận hàng. */
    public enum Fulfilment {
        PICKUP, DELIVERY
    }

    /** Thanh toán đơn online (CARD không đặt online được). COD chỉ với DELIVERY, CASH chỉ với PICKUP (sales kiểm). */
    public enum PaymentMethod {
        BANK_TRANSFER, COD, CASH
    }

    public record LineRequest(@NotNull UUID titleId, @NotNull @Min(1) Integer quantity) {
    }

    public record RecipientRequest(
            @NotBlank @Size(max = 255) @NoControlChars String name,
            @NotBlank @Size(max = 30) @NoControlChars String phone,
            @Size(max = 500) @NoControlChars String address) {

        /** Dữ liệu cá nhân: không in khi bị log nhầm. */
        @Override
        public String toString() {
            return "RecipientRequest[***]";
        }
    }

    /**
     * {@code clientRequestId} là UUID do FE sinh cho mỗi lần bấm đặt hàng (chống đặt trùng khi gửi lại): bắt buộc, sai
     * định dạng thì 400 VALIDATION_FAILED. Gửi lại cùng khóa của chính khách trả đơn cũ, không giữ tồn lần hai.
     */
    public record PlaceOrderRequest(
            @NotEmpty @Size(max = 50) List<@NotNull @Valid LineRequest> lines,
            @NotNull Fulfilment fulfilment,
            @NotNull PaymentMethod paymentMethod,
            @NotNull @Valid RecipientRequest recipient,
            @NotNull UUID clientRequestId) {
    }

    /** Lý do hủy (tùy chọn, tối đa 500). */
    public record CancelOrderRequest(@Size(max = 500) @NoControlChars(allowLineBreaksAndTabs = true) String reason) {
    }

    public record RecipientView(String name, String phone, String address) {

        @Override
        public String toString() {
            return "RecipientView[***]";
        }
    }

    public record OrderLineView(UUID titleId, String titleName, int quantity, long unitPrice, long lineTotal) {
    }

    /**
     * Đơn của khách. KHÔNG có: customerUserId, version, tenantId, channel, contact, cancelReason, isbn, id dòng, giảm giá
     * từng dòng, người thao tác. {@code cancellable}: khách hủy được khi đơn còn {@code PENDING_CONFIRMATION}.
     */
    public record OrderView(UUID id, String orderNo, String status, String paymentStatus, String paymentMethod,
                            String fulfilment, RecipientView recipient, String currency, long subtotal,
                            long discountTotal, long vatTotal, long shippingFee, long grandTotal, Instant createdAt,
                            Instant confirmedAt, Instant paidAt, Instant completedAt, Instant cancelledAt,
                            List<OrderLineView> lines, boolean cancellable) {
    }

    public record OrderPageView(List<OrderView> items, int page, int size, long total) {
    }

    /** Chủ đề liên hệ (khớp ContactTopic của sales). */
    public enum ContactTopic {
        ORDER, PRODUCT, PAYMENT, OTHER
    }

    /**
     * Liên hệ của khách gửi cửa hàng (ADR-0015 3.3). Record ALLOWLIST: không có userId/tenantId/status/orderId (trường lạ
     * trong JSON bị bỏ qua). Giới hạn ở đây chỉ chặn thô vì {@code @Size} đếm đơn vị UTF-16; độ dài chính xác theo
     * code point do sales kiểm. {@code consent} dùng Boolean để thiếu/false trả CONSENT_REQUIRED, chỉ nhận boolean JSON
     * tường minh.
     */
    public record ContactRequestBody(
            @NotNull ContactTopic topic,
            @NotBlank @Size(max = 400) @NoControlChars String fullName,
            @NotBlank @Size(max = 254) @NoControlChars String email,
            @Size(max = 20) @NoControlChars String phone,
            @NotBlank @Size(max = 8000) @NoControlChars(allowLineBreaksAndTabs = true) String message,
            @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean consent) {

        /** Dữ liệu cá nhân: không in khi bị log nhầm. */
        @Override
        public String toString() {
            return "ContactRequestBody[***]";
        }
    }
}
