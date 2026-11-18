package lms.sales.ordering.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

/**
 * Lệnh thanh toán QR của một đơn POS (ADR-0020). Máy trạng thái: PENDING sang PAID (đúng số tiền, chưa hết hạn),
 * EXPIRED (quá hạn) hoặc CANCELLED (thu ngân hủy); ba trạng thái sau là cuối. {@code reference} là mã ngắn duy nhất
 * toàn hệ thống, đóng vai nội dung chuyển khoản để đối soát. Trạng thái không cho phép thì 409
 * PAYMENT_INTENT_STATE_CONFLICT (hoặc mã riêng: EXPIRED, CANCELLED, lệch tiền).
 */
public final class PaymentIntent {

    public static final String PROVIDER_SIMULATED = "SIMULATED";
    /** Cổng sandbox của MoMo (captureWallet); kết quả lấy bằng cách truy vấn MoMo khi FE thăm dò. */
    public static final String PROVIDER_MOMO = "MOMO";
    /** SePay Test mode (ADR-0028): dò sổ giao dịch tiền vào có nội dung chứa mã tham chiếu khi FE thăm dò, không webhook. */
    public static final String PROVIDER_SEPAY = "SEPAY";
    /** Thu tay tại quầy (tiền mặt, thẻ, chuyển khoản đã đối soát): thu ngân bấm xác nhận (ADR-0021). */
    public static final String PROVIDER_MANUAL = "MANUAL";
    public static final int MAX_TRANSACTION_REF = 64;

    /** Toàn bộ trạng thái lưu trữ (dùng cho {@link #restore}). */
    public record Snapshot(UUID id, UUID tenantId, UUID orderId, Money amount, String reference,
                           PaymentIntentStatus status, String provider, String providerTxnId, String qrPayload,
                           Instant expiresAt, Instant paidAt, Instant createdAt, long version,
                           Long tenderedAmount, Long changeDue, String transactionRef) {
        /** Lệnh chưa có thông tin thu tay (cổng tự xác nhận hoặc chưa thu). */
        public Snapshot(UUID id, UUID tenantId, UUID orderId, Money amount, String reference,
                        PaymentIntentStatus status, String provider, String providerTxnId, String qrPayload,
                        Instant expiresAt, Instant paidAt, Instant createdAt, long version) {
            this(id, tenantId, orderId, amount, reference, status, provider, providerTxnId, qrPayload, expiresAt,
                    paidAt, createdAt, version, null, null, null);
        }
    }

    private final UUID id;
    private final UUID tenantId;
    private final UUID orderId;
    private final Money amount;
    private final String reference;
    private PaymentIntentStatus status;
    private final String provider;
    private String providerTxnId;
    private final String qrPayload;
    private final Instant expiresAt;
    private Instant paidAt;
    private final Instant createdAt;
    private final long version;
    private Long tenderedAmount;
    private Long changeDue;
    private String transactionRef;

    private PaymentIntent(Snapshot s) {
        this.id = s.id();
        this.tenantId = s.tenantId();
        this.orderId = s.orderId();
        this.amount = s.amount();
        this.reference = s.reference();
        this.status = s.status();
        this.provider = s.provider();
        this.providerTxnId = s.providerTxnId();
        this.qrPayload = s.qrPayload();
        this.expiresAt = s.expiresAt();
        this.paidAt = s.paidAt();
        this.createdAt = s.createdAt();
        this.version = s.version();
        this.tenderedAmount = s.tenderedAmount();
        this.changeDue = s.changeDue();
        this.transactionRef = s.transactionRef();
    }

    /** Lệnh mới ở PENDING. Số tiền phải dương, hạn phải sau thời điểm tạo. */
    public static PaymentIntent create(UUID tenantId, UUID orderId, Money amount, String reference, String provider,
                                       String qrPayload, Instant now, Instant expiresAt) {
        if (amount == null || amount.amount() <= 0) {
            throw SalesException.invalidRequest("Số tiền thanh toán phải lớn hơn 0");
        }
        if (reference == null || reference.isBlank() || provider == null || provider.isBlank()) {
            throw SalesException.invalidRequest("Thiếu mã tham chiếu hoặc cổng thanh toán");
        }
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw SalesException.invalidRequest("Hạn thanh toán phải sau thời điểm tạo");
        }
        return new PaymentIntent(new Snapshot(UUID.randomUUID(), tenantId, orderId, amount, reference,
                PaymentIntentStatus.PENDING, provider, null, qrPayload, expiresAt, null, now, 0, null, null, null));
    }

    public static PaymentIntent restore(Snapshot snapshot) {
        return new PaymentIntent(snapshot);
    }

    /** Hết hạn theo đồng hồ: đang PENDING và đã qua hạn (hạn là mốc loại trừ: đúng hạn thì hết hạn). */
    public boolean isExpiredAt(Instant now) {
        return status == PaymentIntentStatus.PENDING && !now.isBefore(expiresAt);
    }

    /**
     * Ghi nhận đã nhận tiền. Chỉ từ PENDING; quá hạn thì 409 PAYMENT_INTENT_EXPIRED (không đổi trạng thái, job dọn
     * sẽ chuyển EXPIRED và trả tồn); lệch số tiền thì 400 PAYMENT_AMOUNT_MISMATCH.
     */
    public void markPaid(String txnId, Money paidAmount, Instant now) {
        if (status == PaymentIntentStatus.EXPIRED || isExpiredAt(now)) {
            throw new SalesException(ErrorCode.PAYMENT_INTENT_EXPIRED, "Lệnh thanh toán đã hết hạn");
        }
        if (status == PaymentIntentStatus.CANCELLED) {
            throw new SalesException(ErrorCode.PAYMENT_INTENT_CANCELLED, "Lệnh thanh toán đã bị hủy");
        }
        if (status != PaymentIntentStatus.PENDING) {
            throw conflict();
        }
        if (txnId == null || txnId.isBlank()) {
            throw SalesException.invalidRequest("Thiếu mã giao dịch của cổng thanh toán");
        }
        if (paidAmount == null || paidAmount.amount() != amount.amount()) {
            throw new SalesException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Số tiền không khớp lệnh thanh toán");
        }
        this.status = PaymentIntentStatus.PAID;
        this.providerTxnId = txnId.trim();
        this.paidAt = now;
    }

    /**
     * Thu ngân xác nhận đã thu tay (chỉ lệnh MANUAL, ADR-0021). {@code method} là phương thức của đơn: CASH nhận
     * {@code tendered} (vắng thì bằng đúng số tiền, phải lớn hơn hoặc bằng số tiền; tiền thối = tendered trừ số tiền),
     * CARD/BANK_TRANSFER bắt buộc {@code transactionRef} (tối đa 64 ký tự) và không có tendered. Lệnh hết hạn, đã hủy hoặc
     * đã thu thì 409 PAYMENT_INTENT_STATE_CONFLICT (người gọi xử lý idempotent cho lệnh đã PAID trước khi vào đây).
     */
    public void confirmManual(PaymentMethod method, String ref, Long tendered, Instant now) {
        if (!PROVIDER_MANUAL.equals(provider)) {
            throw new SalesException(ErrorCode.PAYMENT_NOT_MANUAL, "Lệnh thanh toán này không xác nhận thủ công");
        }
        if (status != PaymentIntentStatus.PENDING || isExpiredAt(now)) {
            throw conflict();
        }
        String cleanRef = ref == null || ref.isBlank() ? null : ref.trim();
        if (cleanRef != null && cleanRef.length() > MAX_TRANSACTION_REF) {
            throw SalesException.invalidRequest("Mã giao dịch tối đa " + MAX_TRANSACTION_REF + " ký tự");
        }
        Long tenderedOut = null;
        Long changeOut = null;
        if (method == PaymentMethod.CASH) {
            long t = tendered == null ? amount.amount() : tendered;
            if (t < amount.amount()) {
                throw new SalesException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Tiền khách đưa chưa đủ");
            }
            tenderedOut = t;
            changeOut = t - amount.amount();
        } else if (method == PaymentMethod.CARD || method == PaymentMethod.BANK_TRANSFER) {
            if (tendered != null) {
                throw SalesException.invalidRequest("Chỉ tiền mặt mới có số tiền khách đưa");
            }
            if (cleanRef == null) {
                throw SalesException.invalidRequest("Thiếu mã giao dịch (thẻ, chuyển khoản)");
            }
        } else {
            throw SalesException.invalidRequest("Phương thức thanh toán không xác nhận thủ công");
        }
        this.status = PaymentIntentStatus.PAID;
        this.providerTxnId = "MANUAL-" + id;
        this.transactionRef = cleanRef;
        this.tenderedAmount = tenderedOut;
        this.changeDue = changeOut;
        this.paidAt = now;
    }

    /** PENDING sang EXPIRED; chỉ khi đã qua hạn. */
    public void expire(Instant now) {
        if (!isExpiredAt(now)) {
            throw conflict();
        }
        this.status = PaymentIntentStatus.EXPIRED;
    }

    /** Thu ngân hủy: chỉ từ PENDING. */
    public void cancel() {
        if (status != PaymentIntentStatus.PENDING) {
            throw conflict();
        }
        this.status = PaymentIntentStatus.CANCELLED;
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                "Trạng thái lệnh thanh toán không cho phép thao tác");
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID orderId() { return orderId; }
    public Money amount() { return amount; }
    public String reference() { return reference; }
    public PaymentIntentStatus status() { return status; }
    public String provider() { return provider; }
    public String providerTxnId() { return providerTxnId; }
    public String qrPayload() { return qrPayload; }
    public Instant expiresAt() { return expiresAt; }
    public Instant paidAt() { return paidAt; }
    public Instant createdAt() { return createdAt; }
    public long version() { return version; }
    public Long tenderedAmount() { return tenderedAmount; }
    public Long changeDue() { return changeDue; }
    public String transactionRef() { return transactionRef; }

    @Override
    public String toString() {
        return "PaymentIntent[" + reference + ", " + status + "]";
    }
}
