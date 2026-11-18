package lms.api.sales.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DTO của /api/sales/** (pha 1), theo hợp đồng ở docs/architecture/sales-module-design.md mục 5.
 * Tiền là số nguyên đồng (long, VND). Các trường response ngoài danh sách hợp đồng là theo thiết kế, chưa chốt tới khi
 * sales-service xong (B1.3); trường lạ từ service bị bỏ qua khi đọc.
 */
public final class SalesDtos {

    private SalesDtos() {
    }

    /** Trang kết quả chuẩn của sales-service. */
    public record SalesPage<T>(List<T> items, long total, int page, int size) {
    }

    // ---- Cấu hình ----

    public record SalesSettingsView(int vatRateBps, boolean pricesIncludeVat, int returnWindowDays, long fixedShippingFee,
                                    int lowStockThreshold, int walkInPiiRetentionMonths, long version) {
    }

    public record UpdateSalesSettingsRequest(
            @Min(0) @Max(10000) int vatRateBps,
            boolean pricesIncludeVat,
            @Min(0) @Max(365) int returnWindowDays,
            @Min(0) long fixedShippingFee,
            @Min(0) int lowStockThreshold,
            @Min(1) @Max(120) int walkInPiiRetentionMonths,
            long version) {
    }

    // ---- Mặt hàng, kho ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisterStockItemRequest(@NotNull UUID titleId, @Min(0) Long sellingPrice, @Min(0) Integer lowStockThreshold,
                                           @Size(max = 40) String sku, @Size(max = 255) String supplier,
                                           @Min(0) Long costPrice, @Size(max = 64) String binLocation,
                                           @Min(0) Integer reorderQuantity, Instant lastRestockedAt,
                                           @Size(max = 500) String note) {
    }

    /** Không có costPrice và note (nội bộ): lấy qua {@link StockItemDetailsView} (khóa quyền riêng). */
    public record StockItemView(UUID titleId, String titleName, int onHand, int reserved, int available, Long sellingPrice,
                                long effectivePrice, boolean lowStock, String sku, String supplier, String binLocation,
                                Integer reorderQuantity, Instant lastRestockedAt) {
    }

    /** Chi tiết đầy đủ gồm giá nhập và ghi chú nội bộ. */
    public record StockItemDetailsView(UUID titleId, String sku, String supplier, Long costPrice, String binLocation,
                                       Integer reorderQuantity, Instant lastRestockedAt, String note) {
    }

    /** Thay toàn bộ chi tiết; trường vắng là xóa, riêng lastRestockedAt vắng thì giữ nguyên giá trị hiện có. */
    public record UpdateStockItemDetailsRequest(@Size(max = 40) String sku, @Size(max = 255) String supplier,
                                                @Min(0) Long costPrice, @Size(max = 64) String binLocation,
                                                @Min(0) Integer reorderQuantity, Instant lastRestockedAt,
                                                @Size(max = 500) String note) {
    }

    /** sellingPrice null = quay về giá bìa (coverPrice) của đầu sách: phải gửi tường minh, nên không NON_NULL. */
    public record UpdateStockItemPriceRequest(@Min(0) Long sellingPrice) {
    }

    public record StockItemSearchQuery(String keyword, Boolean lowStockOnly, Integer page, Integer size) {
    }

    public record PageQuery(Integer page, Integer size) {
    }

    /** Dòng sổ cái theo sales-service (B1.3): biến động on_hand/reserved, không có onHandAfter. */
    public record LedgerEntryView(UUID id, UUID titleId, String type, int deltaOnHand, int deltaReserved, String reason,
                                  String refType, UUID refId, UUID actorId, Instant occurredAt) {
    }

    public record StockAdjustmentRequest(@NotNull UUID titleId, int delta, @NotBlank @Size(max = 500) String reason) {
    }

    public record StocktakeRequest(@NotNull UUID titleId, @Min(0) int countedOnHand, @NotBlank @Size(max = 500) String reason) {
    }

    // ---- Nhà cung cấp ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SupplierRequest(@NotBlank @Size(max = 200) String name, @Size(max = 1000) String note,
                                  @Size(max = 30) String phone, @Size(max = 200) String email,
                                  @Size(max = 500) String address, Boolean active) {
    }

    public record SupplierView(UUID id, String name, String note, String phone, String email, String address,
                               boolean active, long version) {
    }

    public record SupplierSearchQuery(String keyword, Boolean activeOnly, Integer page, Integer size) {
    }

    // ---- Nhập kho ----

    public record GoodsReceiptLineRequest(@NotNull UUID titleId, @Min(1) int quantity, @Min(0) long unitCost) {
    }

    public record CreateGoodsReceiptRequest(
            @NotNull UUID supplierId,
            @NotEmpty @Size(max = 200) List<@Valid GoodsReceiptLineRequest> lines,
            @NotBlank @Size(max = 100) String clientRequestId) {
    }

    public record GoodsReceiptLineView(UUID titleId, String titleName, int quantity, long unitCost, long lineCost) {
    }

    public record GoodsReceiptView(UUID id, String receiptNo, UUID supplierId, String supplierName, String status,
                                   List<GoodsReceiptLineView> lines, long totalCost, Instant createdAt, UUID createdBy,
                                   Instant confirmedAt, long version) {
    }

    public record GoodsReceiptSearchQuery(String status, UUID supplierId, Integer page, Integer size) {
    }

    // ---- Đơn POS ----

    public record DiscountRequest(@NotNull @Pattern(regexp = "AMOUNT|PERCENT") String type, @Min(0) long value) {
    }

    /** discount chỉ hợp lệ ở /pos-orders/discounted; gửi ở /pos-orders thì sales-service trả 400 DISCOUNT_NOT_ALLOWED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PosOrderLineRequest(@NotNull UUID titleId, @Min(1) int quantity, @Valid DiscountRequest discount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WalkInRequest(@Size(max = 200) String name, @Size(max = 30) String phone) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PosOrderRequest(
            @NotEmpty @Size(max = 100) List<@Valid PosOrderLineRequest> lines,
            @NotNull @Pattern(regexp = "CASH|BANK_TRANSFER|CARD|QR|MOMO") String paymentMethod,
            UUID customerUserId,
            @Valid WalkInRequest walkIn,
            @Valid DiscountRequest orderDiscount,
            @NotBlank @Size(max = 100) String clientRequestId,
            UUID exchangeOfReturnId,
            @Size(max = 500) String customerNote) {
    }

    // ---- Đọc đơn ----

    public record OrderContactView(String name, String phone, String address, Instant piiAnonymizedAt) {
    }

    public record OrderLineView(UUID id, int lineNo, UUID titleId, String titleName, String isbn, int quantity,
                                long unitPrice, long lineDiscount, long allocatedOrderDiscount, long amount, long vatAmount,
                                long lineTotal) {
    }

    /**
     * Dùng cho cả danh sách (lines có thể rỗng) và chi tiết; liên hệ trong view là bản che một phần (đơn online: người
     * nhận, đơn POS: khách vãng lai).
     */
    public record SalesOrderView(UUID id, String orderNo, String status, String paymentStatus, String channel,
                                 String fulfilment, String paymentMethod, UUID customerUserId, OrderContactView contact,
                                 List<OrderLineView> lines, long subtotal, long discountTotal, long vatTotal,
                                 long shippingFee, long grandTotal, int vatRateBps, boolean pricesIncludeVat,
                                 Instant createdAt, Instant confirmedAt, Instant paidAt, Instant completedAt,
                                 Instant cancelledAt, String cancelReason, UUID exchangeOfReturnId, long version,
                                 String customerNote, LocalDate expectedDeliveryDate,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) PaymentIntentView paymentIntent) {

        /** Đơn không kèm lệnh thanh toán (mọi đường đọc/tìm đơn, và POS không dùng QR). */
        public SalesOrderView(UUID id, String orderNo, String status, String paymentStatus, String channel,
                              String fulfilment, String paymentMethod, UUID customerUserId, OrderContactView contact,
                              List<OrderLineView> lines, long subtotal, long discountTotal, long vatTotal,
                              long shippingFee, long grandTotal, int vatRateBps, boolean pricesIncludeVat,
                              Instant createdAt, Instant confirmedAt, Instant paidAt, Instant completedAt,
                              Instant cancelledAt, String cancelReason, UUID exchangeOfReturnId, long version,
                              String customerNote, LocalDate expectedDeliveryDate) {
            this(id, orderNo, status, paymentStatus, channel, fulfilment, paymentMethod, customerUserId, contact,
                    lines, subtotal, discountTotal, vatTotal, shippingFee, grandTotal, vatRateBps, pricesIncludeVat,
                    createdAt, confirmedAt, paidAt, completedAt, cancelledAt, cancelReason, exchangeOfReturnId,
                    version, customerNote, expectedDeliveryDate, null);
        }
    }

    /**
     * Lệnh thanh toán của đơn POS (ADR-0020, ADR-0021). provider: MANUAL (CASH/CARD/BANK_TRANSFER, thu ngân xác nhận,
     * qrPayload null) hoặc SIMULATED (QR, tự xác nhận qua webhook). status: PENDING, PAID, EXPIRED, CANCELLED (EXPIRED báo
     * ngay khi quá hạn dù job dọn chưa chạy). qrPayload là chuỗi VietQR/EMVCo để FE vẽ mã QR; amount là số đồng;
     * tenderedAmount/changeDue chỉ có với tiền mặt đã xác nhận; transactionRef với lệnh đã xác nhận có mã.
     */
    public record PaymentIntentView(UUID id, UUID orderId, String reference, long amount, String status,
                                    String provider, String qrPayload, Instant expiresAt, Instant paidAt,
                                    Instant createdAt, Long tenderedAmount, Long changeDue, String transactionRef) {
    }

    /**
     * Xác nhận thu tay (lệnh provider MANUAL, ADR-0021). tenderedAmount chỉ tiền mặt (vắng thì bằng đúng số tiền, phải
     * lớn hơn hoặc bằng số tiền); transactionRef bắt buộc với thẻ/chuyển khoản, tối đa 64 ký tự.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfirmPaymentRequest(@Min(0) Long tenderedAmount, @Size(max = 64) String transactionRef) {
    }

    /** Ghi chú đầy đủ của đơn gồm staffNote (nội bộ, không có ở SalesOrderView); khóa quyền riêng. */
    public record OrderNotesView(UUID id, String customerNote, String staffNote, LocalDate expectedDeliveryDate,
                                 long version) {
    }

    /** Thay toàn bộ ghi chú nội bộ và ngày giao/nhận dự kiến (vắng là xóa); version tùy chọn (lệch thì 409). */
    public record UpdateOrderNotesRequest(@Size(max = 500) String staffNote, LocalDate expectedDeliveryDate,
                                          @Min(0) Long version) {
    }

    public record SalesOrderSearchQuery(String status, String paymentStatus, String channel, String from, String to,
                                        String keyword, Integer page, Integer size) {
    }

    public record ReceiptLineView(int lineNo, String titleName, String isbn, int quantity, long unitPrice, long discount,
                                  long amount, long vatAmount, long lineTotal) {
    }

    public record ReceiptView(UUID orderId, String orderNo, Instant issuedAt, List<ReceiptLineView> lines, long subtotal,
                              long discountTotal, long vatTotal, long shippingFee, long grandTotal, int vatRateBps,
                              boolean pricesIncludeVat, String paymentMethod) {
    }

    // ---- Thao tác đơn online (nhân viên), pha 2 ----

    /** Thân tùy chọn của ready-for-pickup, shipping, completion. */
    public record OrderVersionRequest(@Min(0) Long version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConfirmOrderRequest(@Min(0) Long shippingFee, @Min(0) Long version) {
    }

    public record LineDiscountRequest(@NotNull @Min(1) Integer lineNo, @NotNull @Valid DiscountRequest discount) {
    }

    /** Mỗi lần gọi thay toàn bộ giảm giá; bỏ trống cả hai là gỡ giảm giá. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApplyOrderDiscountRequest(@Valid DiscountRequest orderDiscount,
                                            List<@Valid @NotNull LineDiscountRequest> lineDiscounts,
                                            @Min(0) Long version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarkOrderPaidRequest(@NotNull @Pattern(regexp = "CASH|BANK_TRANSFER|CARD|COD") String method,
                                       @Size(max = 100) String reference, @Min(0) Long version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CancelOrderRequest(@NotBlank @Size(max = 500) String reason, @Min(0) Long version) {
    }

    // ---- Trả hàng, hoàn tiền ----

    public record ReturnLineRequest(@NotNull UUID orderLineId, @NotNull @Min(1) Integer quantity,
                                    @NotNull @Pattern(regexp = "RESTOCK|WRITE_OFF") String disposition) {
    }

    public record CreateReturnRequest(@NotNull UUID orderId,
                                      @NotEmpty @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> lines,
                                      @NotBlank @Size(max = 500) String reason,
                                      @NotBlank @Size(max = 100) String clientRequestId) {
    }

    /**
     * Trả quá hạn. {@code lateReason} KHÔNG bắt buộc ở BFF: thiếu hoặc rỗng thì sales-service trả
     * {@code 400 LATE_REASON_REQUIRED} (chuyển nguyên).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateLateReturnRequest(@NotNull UUID orderId,
                                          @NotEmpty @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> lines,
                                          @NotBlank @Size(max = 500) String reason,
                                          @Size(max = 500) String lateReason,
                                          @NotBlank @Size(max = 100) String clientRequestId) {
    }

    public record ReturnLineView(UUID id, UUID orderLineId, UUID titleId, int quantity, String disposition,
                                 long refundAmount) {
    }

    public record ReturnView(UUID id, String returnNo, UUID orderId, String reason, boolean late, String lateReason,
                             int daysOverdue, long refundTotal, UUID refundId, UUID createdBy, Instant createdAt,
                             List<ReturnLineView> lines) {
    }

    public record RefundSearchQuery(String status, Integer page, Integer size) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarkRefundedRequest(@NotNull @Pattern(regexp = "CASH|BANK_TRANSFER|CARD|COD") String method,
                                      @Size(max = 100) String reference, @Min(0) Long version) {
    }

    public record RefundView(UUID id, UUID orderId, String source, UUID returnId, long amount, String status,
                             String method, String reference, Instant createdAt, UUID refundedBy, Instant refundedAt,
                             long version) {
    }

    // ---- Nhật ký ----

    /** from bao gồm, to loại trừ, ISO-8601 (chuỗi, chuyển nguyên cho sales-service kiểm). */
    public record AuditLogQuery(String action, String aggregateType, UUID aggregateId, String from, String to,
                                Integer page, Integer size) {
    }

    public record AuditLogEntryView(UUID id, String action, String aggregateType, UUID aggregateId, UUID actorId,
                                    Instant occurredAt, Map<String, Object> data) {
    }

    // ---- Liên hệ của khách gửi cửa hàng (ADR-0015) ----

    /** from bao gồm, to loại trừ, theo thời điểm tạo (ISO-8601); status NEW|IN_PROGRESS|CLOSED; topic ORDER|PRODUCT|PAYMENT|OTHER. */
    public record ShopContactSearchQuery(String status, String topic, String from, String to, Integer page, Integer size) {
    }

    /** Liên hệ đã che (danh sách/chi tiết). */
    public record ShopContactMaskedContact(String name, String email, String phone) {
        @Override
        public String toString() {
            return "ShopContactMaskedContact[***]";
        }
    }

    public record ShopContactSummaryView(UUID id, String topic, String status, Instant createdAt, Instant closedAt,
                                         ShopContactMaskedContact contact, Instant piiAnonymizedAt, long version) {
        @Override
        public String toString() {
            return "ShopContactSummaryView[id=" + id + ", ***]";
        }
    }

    public record ShopContactDetailView(UUID id, String topic, String status, Instant createdAt, Instant closedAt,
                                        ShopContactMaskedContact contact, Instant piiAnonymizedAt, long version,
                                        String message, UUID customerUserId) {
        @Override
        public String toString() {
            return "ShopContactDetailView[id=" + id + ", ***]";
        }
    }

    /** Liên hệ đầy đủ (khóa getContact); đã ẩn danh thì các trường là null. */
    public record ShopContactContactView(String fullName, String email, String phone, Instant piiAnonymizedAt) {
        @Override
        public String toString() {
            return "ShopContactContactView[***]";
        }
    }

    public record UpdateShopContactStatusRequest(@NotNull String status, @NotNull @Min(0) Long version) {
    }

    // ---- Quản trị banner trang chủ (ADR-0019 B5) ----

    /**
     * Thân tạo/sửa banner. Quy tắc trường do sales kiểm (INVALID_BANNER); lms-api chỉ chặn thô kích thước.
     * version chỉ dùng ở PUT (sales bắt buộc), POST bỏ qua.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SaveBannerRequest(@Size(max = 1000) String title, @Size(max = 1000) String subtitle,
                                    @Size(max = 1000) String linkPath, Integer sortOrder, Boolean active,
                                    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startsAt,
                                    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant endsAt, Long version) {
    }

    public record BannerAdminView(UUID id, String title, String subtitle, String linkPath, int sortOrder,
                                  boolean active, Instant startsAt, Instant endsAt, String status, long version,
                                  Instant createdAt, Instant updatedAt) {
    }
}
