package lms.sales.invoicing.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

/**
 * Aggregate hóa đơn (ADR-0030 D1). Máy trạng thái DRAFT, ISSUED, CANCELLED. DRAFT chưa có số; {@link #issue} gán số và
 * từ đó MỌI trường (người bán, người mua, dòng, tiền, số) là bất biến: aggregate không có setter, chỉ còn
 * {@link #cancel} (ISSUED sang CANCELLED kèm lý do) và cột tích hợp {@code external*}. Không có thao tác xóa. Tiền tính từ
 * dòng: tổng trước thuế, tổng thuế, tổng thanh toán; grandTotal bằng tổng thành tiền của mọi dòng (kể cả phí ship).
 * Hóa đơn nội bộ CHƯA có giá trị pháp lý của hóa đơn điện tử.
 */
public final class Invoice {

    public static final int MAX_CANCEL_REASON = 500;
    public static final String ISSUER_INTERNAL = "INTERNAL";

    /** Toàn bộ trạng thái lưu trữ (dùng cho {@link #restore}). */
    public record Snapshot(UUID id, UUID tenantId, InvoiceKind kind, InvoiceStatus status, UUID orderId,
                           String orderNo, UUID returnId, UUID originalInvoiceId, String templateCode,
                           String series, Integer fiscalYear, Long invoiceNo, Instant issuedAt, UUID issuedBy,
                           Instant cancelledAt, UUID cancelledBy, String cancelReason, SellerInfo seller,
                           BuyerInfo buyer, boolean pricesIncludeVat, String paymentMethod, long totalBeforeVat,
                           long vatTotal, long grandTotal, String amountInWords, String issuer, String externalId,
                           String externalStatus, String externalLookupCode, UUID clientRequestId, UUID createdBy,
                           Instant createdAt, List<InvoiceLine> lines, long version) {
        @Override
        public String toString() {
            return "Snapshot[" + id + "]";
        }
    }

    private final UUID id;
    private final UUID tenantId;
    private final InvoiceKind kind;
    private InvoiceStatus status;
    private final UUID orderId;
    private final String orderNo;
    private final UUID returnId;
    private final UUID originalInvoiceId;
    private final String templateCode;
    private final String series;
    private Integer fiscalYear;
    private Long invoiceNo;
    private Instant issuedAt;
    private UUID issuedBy;
    private Instant cancelledAt;
    private UUID cancelledBy;
    private String cancelReason;
    private final SellerInfo seller;
    private final BuyerInfo buyer;
    private final boolean pricesIncludeVat;
    private final String paymentMethod;
    private final Money totalBeforeVat;
    private final Money vatTotal;
    private final Money grandTotal;
    private String amountInWords;
    private String issuer;
    private String externalId;
    private String externalStatus;
    private String externalLookupCode;
    private final UUID clientRequestId;
    private final UUID createdBy;
    private final Instant createdAt;
    private final List<InvoiceLine> lines;
    private final long version;

    private Invoice(Snapshot s) {
        this.id = s.id();
        this.tenantId = s.tenantId();
        this.kind = s.kind();
        this.status = s.status();
        this.orderId = s.orderId();
        this.orderNo = s.orderNo();
        this.returnId = s.returnId();
        this.originalInvoiceId = s.originalInvoiceId();
        this.templateCode = s.templateCode();
        this.series = s.series();
        this.fiscalYear = s.fiscalYear();
        this.invoiceNo = s.invoiceNo();
        this.issuedAt = s.issuedAt();
        this.issuedBy = s.issuedBy();
        this.cancelledAt = s.cancelledAt();
        this.cancelledBy = s.cancelledBy();
        this.cancelReason = s.cancelReason();
        this.seller = s.seller();
        this.buyer = s.buyer();
        this.pricesIncludeVat = s.pricesIncludeVat();
        this.paymentMethod = s.paymentMethod();
        this.amountInWords = s.amountInWords();
        this.issuer = s.issuer();
        this.externalId = s.externalId();
        this.externalStatus = s.externalStatus();
        this.externalLookupCode = s.externalLookupCode();
        this.clientRequestId = s.clientRequestId();
        this.createdBy = s.createdBy();
        this.createdAt = s.createdAt();
        this.lines = List.copyOf(s.lines());
        this.version = s.version();
        long before = 0;
        long vat = 0;
        long total = 0;
        for (InvoiceLine l : lines) {
            before = Math.addExact(before, l.amountBeforeVat().amount());
            vat = Math.addExact(vat, l.vatAmount().amount());
            total = Math.addExact(total, l.lineTotal().amount());
        }
        this.totalBeforeVat = Money.of(before);
        this.vatTotal = Money.of(vat);
        this.grandTotal = Money.of(total);
        if (s.totalBeforeVat() != before || s.vatTotal() != vat || s.grandTotal() != total) {
            throw new IllegalStateException("Tổng hóa đơn không khớp tổng các dòng");
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("Hóa đơn cần ít nhất một dòng");
        }
    }

    /** Hóa đơn gốc của đơn (DRAFT). */
    public static Invoice draftOriginal(UUID id, UUID tenantId, UUID orderId, String orderNo, String templateCode,
                                        String series, SellerInfo seller, BuyerInfo buyer, boolean pricesIncludeVat,
                                        String paymentMethod, List<InvoiceLine> lines, UUID createdBy, Instant now,
                                        UUID clientRequestId) {
        return create(id, tenantId, InvoiceKind.ORIGINAL, orderId, orderNo, null, null, templateCode, series, seller,
                buyer, pricesIncludeVat, paymentMethod, lines, createdBy, now, clientRequestId);
    }

    /**
     * Hóa đơn thay thế {@code original} (use case đã hủy bản gốc): sao chép dòng và cờ giá, thay người bán/người mua,
     * mẫu số/ký hiệu theo cấu hình hiện tại.
     */
    public static Invoice draftReplacement(UUID id, Invoice original, String templateCode, String series,
                                           SellerInfo seller, BuyerInfo buyer, UUID createdBy, Instant now,
                                           UUID clientRequestId) {
        return create(id, original.tenantId, InvoiceKind.REPLACEMENT, original.orderId, original.orderNo, null,
                original.id, templateCode, series, seller, buyer, original.pricesIncludeVat, original.paymentMethod,
                original.lines, createdBy, now, clientRequestId);
    }

    /**
     * Hóa đơn điều chỉnh giảm cho {@code original} do phiếu trả {@code returnId}: dùng người bán, người mua, mẫu số và
     * ký hiệu của hóa đơn gốc (điều chỉnh gắn với gốc, không phụ thuộc cấu hình hiện tại).
     */
    public static Invoice draftAdjustment(UUID id, Invoice original, UUID returnId, List<InvoiceLine> lines,
                                          UUID createdBy, Instant now) {
        if (returnId == null) {
            throw new IllegalArgumentException("Hóa đơn điều chỉnh cần phiếu trả");
        }
        return create(id, original.tenantId, InvoiceKind.ADJUSTMENT_DECREASE, original.orderId, original.orderNo,
                returnId, original.id, original.templateCode, original.series, original.seller, original.buyer,
                original.pricesIncludeVat, original.paymentMethod, lines, createdBy, now, null);
    }

    private static Invoice create(UUID id, UUID tenantId, InvoiceKind kind, UUID orderId, String orderNo,
                                  UUID returnId, UUID originalId, String templateCode, String series,
                                  SellerInfo seller, BuyerInfo buyer, boolean pricesIncludeVat,
                                  String paymentMethod, List<InvoiceLine> lines, UUID createdBy, Instant now,
                                  UUID clientRequestId) {
        if (lines == null || lines.isEmpty()) {
            throw SalesException.invalidRequest("Hóa đơn cần ít nhất một dòng");
        }
        long before = 0;
        long vat = 0;
        long total = 0;
        for (InvoiceLine l : lines) {
            before += l.amountBeforeVat().amount();
            vat += l.vatAmount().amount();
            total += l.lineTotal().amount();
        }
        return new Invoice(new Snapshot(id, tenantId, kind, InvoiceStatus.DRAFT, orderId, orderNo, returnId,
                originalId, templateCode, series, null, null, null, null, null, null, null, seller, buyer,
                pricesIncludeVat, paymentMethod, before, vat, total, null, ISSUER_INTERNAL, null, null, null,
                clientRequestId, createdBy, now, lines, 0));
    }

    public static Invoice restore(Snapshot snapshot) {
        return new Invoice(snapshot);
    }

    /**
     * DRAFT sang ISSUED: gán số, năm, người/thời điểm phát hành, nhà cung cấp và số tiền bằng chữ. Từ đây bất biến.
     * {@code invoiceNo} do {@code InvoiceIssuerPort} cấp (số nội bộ lấy từ bộ đếm cùng transaction).
     */
    public void issue(long number, int year, String issuerCode, UUID by, Instant now) {
        if (status != InvoiceStatus.DRAFT) {
            throw stateConflict("Chỉ hóa đơn nháp mới phát hành được");
        }
        if (number <= 0) {
            throw new IllegalArgumentException("Số hóa đơn phải dương");
        }
        this.invoiceNo = number;
        this.fiscalYear = year;
        this.issuedAt = now;
        this.issuedBy = by;
        this.issuer = issuerCode == null || issuerCode.isBlank() ? ISSUER_INTERNAL : issuerCode;
        this.amountInWords = VietnameseAmountInWords.of(grandTotal.amount());
        this.status = InvoiceStatus.ISSUED;
    }

    /**
     * Hủy hóa đơn đã phát hành. Chỉ từ ISSUED, lý do bắt buộc. Hóa đơn điều chỉnh không hủy được. Hóa đơn gốc/thay thế
     * đã có điều chỉnh còn hiệu lực thì không hủy (và không thay thế) được: 409 INVOICE_HAS_ADJUSTMENTS.
     */
    public void cancel(String reason, UUID by, Instant now, boolean hasActiveAdjustments) {
        if (status != InvoiceStatus.ISSUED) {
            throw stateConflict("Chỉ hóa đơn đã phát hành mới hủy được");
        }
        if (kind == InvoiceKind.ADJUSTMENT_DECREASE) {
            throw stateConflict("Hóa đơn điều chỉnh không hủy được");
        }
        String r = requireReason(reason);
        if (hasActiveAdjustments) {
            throw new SalesException(ErrorCode.INVOICE_HAS_ADJUSTMENTS,
                    "Hóa đơn đã có hóa đơn điều chỉnh nên không hủy hoặc thay thế được");
        }
        this.status = InvoiceStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelledBy = by;
        this.cancelReason = r;
    }

    /**
     * Ghi kết quả của nhà cung cấp hóa đơn điện tử (mã tra cứu, trạng thái). Đây là nhóm cột tích hợp duy nhất còn đổi
     * được sau khi phát hành; hóa đơn nội bộ không dùng.
     */
    public void attachExternal(String id, String statusText, String lookupCode) {
        this.externalId = id;
        this.externalStatus = statusText;
        this.externalLookupCode = lookupCode;
    }

    /** Bỏ hóa đơn nháp: chuyển CANCELLED kèm lý do (không bao giờ xóa). */
    public void abandonDraft(String reason, UUID by, Instant now) {
        if (status != InvoiceStatus.DRAFT) {
            throw stateConflict("Chỉ hóa đơn nháp mới bỏ được");
        }
        this.cancelReason = requireReason(reason);
        this.cancelledAt = now;
        this.cancelledBy = by;
        this.status = InvoiceStatus.CANCELLED;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new SalesException(ErrorCode.CANCEL_REASON_REQUIRED, "Bắt buộc nhập lý do hủy");
        }
        String r = reason.trim();
        if (r.length() > MAX_CANCEL_REASON) {
            throw SalesException.invalidRequest("Lý do tối đa " + MAX_CANCEL_REASON + " ký tự");
        }
        return r;
    }

    private static SalesException stateConflict(String message) {
        return new SalesException(ErrorCode.INVOICE_STATE_CONFLICT, message);
    }

    /** Đang có hiệu lực (đã phát hành, chưa hủy). */
    public boolean isEffective() {
        return status == InvoiceStatus.ISSUED;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public InvoiceKind kind() { return kind; }
    public InvoiceStatus status() { return status; }
    public UUID orderId() { return orderId; }
    public String orderNo() { return orderNo; }
    public UUID returnId() { return returnId; }
    public UUID originalInvoiceId() { return originalInvoiceId; }
    public String templateCode() { return templateCode; }
    public String series() { return series; }
    public Integer fiscalYear() { return fiscalYear; }
    public Long invoiceNo() { return invoiceNo; }
    public Instant issuedAt() { return issuedAt; }
    public UUID issuedBy() { return issuedBy; }
    public Instant cancelledAt() { return cancelledAt; }
    public UUID cancelledBy() { return cancelledBy; }
    public String cancelReason() { return cancelReason; }
    public SellerInfo seller() { return seller; }
    public BuyerInfo buyer() { return buyer; }
    public boolean pricesIncludeVat() { return pricesIncludeVat; }
    public String paymentMethod() { return paymentMethod; }
    public Money totalBeforeVat() { return totalBeforeVat; }
    public Money vatTotal() { return vatTotal; }
    public Money grandTotal() { return grandTotal; }
    public String amountInWords() { return amountInWords; }
    public String issuer() { return issuer; }
    public String externalId() { return externalId; }
    public String externalStatus() { return externalStatus; }
    public String externalLookupCode() { return externalLookupCode; }
    public UUID clientRequestId() { return clientRequestId; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public List<InvoiceLine> lines() { return lines; }
    public long version() { return version; }

    /** Số hiển thị đệm 8 chữ số; null khi chưa phát hành. */
    public String displayNumber() {
        return invoiceNo == null ? null : InvoiceNumbers.display(invoiceNo);
    }

    /** Không in dữ liệu cá nhân (người mua). */
    @Override
    public String toString() {
        return "Invoice[" + id + ", " + kind + ", " + status + ", no=" + invoiceNo + ", total="
                + grandTotal.amount() + "]";
    }
}
