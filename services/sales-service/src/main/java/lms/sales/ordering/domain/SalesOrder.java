package lms.sales.ordering.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

/**
 * Aggregate đơn hàng. POS: một bước (COMPLETED + PAID ngay). Online: máy trạng thái PENDING_CONFIRMATION, CONFIRMED,
 * READY_FOR_PICKUP, SHIPPING, COMPLETED, CANCELLED; thanh toán là trục riêng (UNPAID, PAID, hoàn tiền).
 * Snapshot thuế suất, cờ giá gồm thuế, đơn giá, giảm giá, VAT từng dòng, phí ship nằm trên đơn: đổi cấu hình sau đó
 * không đổi đơn cũ. Bất biến: tổng dòng + phí ship = tổng đơn; không số tiền nào âm. Mọi chuyển trạng thái nằm ở đây;
 * trạng thái không cho phép thì 409 ORDER_STATE_CONFLICT.
 */
public final class SalesOrder {

    public static final int MAX_NOTE = 500;

    /** Dòng đầu vào lúc lập đơn, đã resolve giá bán và snapshot đầu sách. */
    public record LineDraft(UUID titleId, String titleName, String isbn, Money unitPrice, int quantity,
                            Discount lineDiscount) {
    }

    /** Toàn bộ trạng thái lưu trữ của đơn (dùng cho {@link #restore}). */
    public record Snapshot(UUID id, UUID tenantId, String orderNo, OrderChannel channel, OrderStatus status,
                           PaymentStatus paymentStatus, PaymentMethod paymentMethod, UUID customerUserId,
                           String walkInName, String walkInPhone, Fulfilment fulfilment, Recipient recipient,
                           int vatRateBps, boolean pricesIncludeVat, Money subtotal, Money discountTotal,
                           Money vatTotal, Money shippingFee, Money grandTotal, Discount orderDiscount,
                           UUID clientRequestId, UUID exchangeOfReturnId, UUID createdBy, Instant createdAt,
                           Instant confirmedAt, UUID confirmedBy, Instant paidAt, Instant completedAt,
                           Instant cancelledAt, UUID cancelledBy, String cancelReason, Instant piiAnonymizedAt,
                           List<OrderLine> lines, List<PaymentRecord> payments, long version,
                           String customerNote, String staffNote, LocalDate expectedDeliveryDate) {
        /** Đơn không có ghi chú và ngày giao dự kiến. */
        public Snapshot(UUID id, UUID tenantId, String orderNo, OrderChannel channel, OrderStatus status,
                        PaymentStatus paymentStatus, PaymentMethod paymentMethod, UUID customerUserId,
                        String walkInName, String walkInPhone, Fulfilment fulfilment, Recipient recipient,
                        int vatRateBps, boolean pricesIncludeVat, Money subtotal, Money discountTotal,
                        Money vatTotal, Money shippingFee, Money grandTotal, Discount orderDiscount,
                        UUID clientRequestId, UUID exchangeOfReturnId, UUID createdBy, Instant createdAt,
                        Instant confirmedAt, UUID confirmedBy, Instant paidAt, Instant completedAt,
                        Instant cancelledAt, UUID cancelledBy, String cancelReason, Instant piiAnonymizedAt,
                        List<OrderLine> lines, List<PaymentRecord> payments, long version) {
            this(id, tenantId, orderNo, channel, status, paymentStatus, paymentMethod, customerUserId, walkInName,
                    walkInPhone, fulfilment, recipient, vatRateBps, pricesIncludeVat, subtotal, discountTotal,
                    vatTotal, shippingFee, grandTotal, orderDiscount, clientRequestId, exchangeOfReturnId, createdBy,
                    createdAt, confirmedAt, confirmedBy, paidAt, completedAt, cancelledAt, cancelledBy, cancelReason,
                    piiAnonymizedAt, lines, payments, version, null, null, null);
        }

        @Override
        public String toString() {
            return "Snapshot[" + orderNo + "]";
        }
    }

    private final UUID id;
    private final UUID tenantId;
    private final String orderNo;
    private final OrderChannel channel;
    private OrderStatus status;
    private PaymentStatus paymentStatus;
    private final PaymentMethod paymentMethod;
    private final UUID customerUserId;
    private String walkInName;
    private String walkInPhone;
    private final Fulfilment fulfilment;
    private final Recipient recipient;
    private final int vatRateBps;
    private final boolean pricesIncludeVat;
    private Money subtotal;
    private Money discountTotal;
    private Money vatTotal;
    private Money shippingFee;
    private Money grandTotal;
    private Discount orderDiscount;
    private final UUID clientRequestId;
    private final UUID exchangeOfReturnId;
    private final UUID createdBy;
    private final Instant createdAt;
    private Instant confirmedAt;
    private UUID confirmedBy;
    private Instant paidAt;
    private Instant completedAt;
    private Instant cancelledAt;
    private UUID cancelledBy;
    private String cancelReason;
    private Instant piiAnonymizedAt;
    private String customerNote;
    private String staffNote;
    private LocalDate expectedDeliveryDate;
    private List<OrderLine> lines;
    private final List<PaymentRecord> payments;
    private final long version;

    private SalesOrder(Snapshot s) {
        this.id = s.id();
        this.tenantId = s.tenantId();
        this.orderNo = s.orderNo();
        this.channel = s.channel();
        this.status = s.status();
        this.paymentStatus = s.paymentStatus();
        this.paymentMethod = s.paymentMethod();
        this.customerUserId = s.customerUserId();
        this.walkInName = s.walkInName();
        this.walkInPhone = s.walkInPhone();
        this.fulfilment = s.fulfilment();
        this.recipient = s.recipient();
        this.vatRateBps = s.vatRateBps();
        this.pricesIncludeVat = s.pricesIncludeVat();
        this.subtotal = s.subtotal();
        this.discountTotal = s.discountTotal();
        this.vatTotal = s.vatTotal();
        this.shippingFee = s.shippingFee();
        this.grandTotal = s.grandTotal();
        this.orderDiscount = s.orderDiscount();
        this.clientRequestId = s.clientRequestId();
        this.exchangeOfReturnId = s.exchangeOfReturnId();
        this.createdBy = s.createdBy();
        this.createdAt = s.createdAt();
        this.confirmedAt = s.confirmedAt();
        this.confirmedBy = s.confirmedBy();
        this.paidAt = s.paidAt();
        this.completedAt = s.completedAt();
        this.cancelledAt = s.cancelledAt();
        this.cancelledBy = s.cancelledBy();
        this.cancelReason = s.cancelReason();
        this.piiAnonymizedAt = s.piiAnonymizedAt();
        this.customerNote = s.customerNote();
        this.staffNote = s.staffNote();
        this.expectedDeliveryDate = s.expectedDeliveryDate();
        this.lines = List.copyOf(s.lines());
        this.payments = new ArrayList<>(s.payments());
        this.version = s.version();
        checkTotal();
    }

    private void checkTotal() {
        long sum = 0;
        for (OrderLine l : lines) {
            sum += l.lineTotal().amount();
        }
        if (sum + shippingFee.amount() != grandTotal.amount()) {
            throw new IllegalStateException("Tổng đơn không khớp tổng các dòng cộng phí ship");
        }
    }

    /** Đơn POS: trừ kho do use case làm; ở đây chỉ tính tiền và đặt COMPLETED + PAID với một bản ghi thanh toán. */
    public static SalesOrder createPos(UUID tenantId, String orderNo, UUID createdBy, Instant now,
                                       PaymentMethod method, UUID customerUserId, String walkInName,
                                       String walkInPhone, List<LineDraft> drafts, Discount orderDiscount,
                                       PriceCalculator.TaxPolicy tax, UUID clientRequestId) {
        return createPos(tenantId, orderNo, createdBy, now, method, customerUserId, walkInName, walkInPhone, drafts,
                orderDiscount, tax, clientRequestId, null);
    }

    /** Như trên, kèm phiếu trả được đổi (đơn đổi hàng). */
    public static SalesOrder createPos(UUID tenantId, String orderNo, UUID createdBy, Instant now,
                                       PaymentMethod method, UUID customerUserId, String walkInName,
                                       String walkInPhone, List<LineDraft> drafts, Discount orderDiscount,
                                       PriceCalculator.TaxPolicy tax, UUID clientRequestId,
                                       UUID exchangeOfReturnId) {
        if (method == null || !method.allowedForPos() || method.isGateway()) {
            // QR/MoMo không thu xong ngay: dùng createPosAwaitingPayment
            throw SalesException.invalidRequest("Phương thức thanh toán không hợp lệ cho bán tại quầy");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw SalesException.invalidRequest("Đơn cần ít nhất một dòng");
        }
        var result = calculate(drafts, orderDiscount, tax, Money.ZERO);
        List<OrderLine> lines = buildLines(drafts, result);
        PaymentRecord payment = new PaymentRecord(UUID.randomUUID(), result.grandTotal(), method, null, createdBy,
                now);
        return new SalesOrder(new Snapshot(UUID.randomUUID(), tenantId, orderNo, OrderChannel.POS,
                OrderStatus.COMPLETED, PaymentStatus.PAID, method, customerUserId, blankToNull(walkInName),
                blankToNull(walkInPhone), Fulfilment.COUNTER, null, tax.vatRate().bps(), tax.pricesIncludeVat(),
                result.subtotal(), result.discountTotal(), result.vatTotal(), result.shippingFee(),
                result.grandTotal(), orderDiscount, clientRequestId, exchangeOfReturnId, createdBy, now, null, null,
                now, now, null, null, null, null, lines, List.of(payment), 0));
    }

    /**
     * Đơn online do khách đặt: PENDING_CONFIRMATION, UNPAID, chưa phí ship (nhân viên đặt khi xác nhận). Hàng được
     * giữ (reserved) bởi use case trong cùng transaction. COD chỉ đi với giao hàng, tiền mặt chỉ với nhận tại quầy.
     */
    public static SalesOrder placeOnline(UUID tenantId, String orderNo, UUID customerUserId, Instant now,
                                         PaymentMethod method, Fulfilment fulfilment, Recipient recipient,
                                         List<LineDraft> drafts, PriceCalculator.TaxPolicy tax,
                                         UUID clientRequestId) {
        if (fulfilment == null || fulfilment == Fulfilment.COUNTER) {
            throw SalesException.invalidRequest("Hình thức nhận hàng phải là nhận tại cửa hàng hoặc giao hàng");
        }
        if (method == null || method == PaymentMethod.CARD || method.isGateway()
                || (method == PaymentMethod.COD && fulfilment != Fulfilment.DELIVERY)
                || (method == PaymentMethod.CASH && fulfilment != Fulfilment.PICKUP)) {
            throw SalesException.invalidRequest("Phương thức thanh toán không phù hợp với hình thức nhận hàng");
        }
        if (customerUserId == null) {
            throw SalesException.invalidRequest("Đơn online cần khách đã đăng nhập");
        }
        if (recipient == null || isBlank(recipient.name()) || isBlank(recipient.phone())
                || (fulfilment == Fulfilment.DELIVERY && isBlank(recipient.address()))) {
            throw SalesException.invalidRequest("Thiếu thông tin người nhận");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw SalesException.invalidRequest("Đơn cần ít nhất một dòng");
        }
        var result = calculate(drafts, null, tax, Money.ZERO);
        return new SalesOrder(new Snapshot(UUID.randomUUID(), tenantId, orderNo, OrderChannel.ONLINE,
                OrderStatus.PENDING_CONFIRMATION, PaymentStatus.UNPAID, method, customerUserId, null, null,
                fulfilment, new Recipient(recipient.name().trim(), recipient.phone().trim(),
                blankToNull(recipient.address())), tax.vatRate().bps(), tax.pricesIncludeVat(), result.subtotal(),
                result.discountTotal(), result.vatTotal(), result.shippingFee(), result.grandTotal(), null,
                clientRequestId, null, customerUserId, now, null, null, null, null, null, null, null, null,
                buildLines(drafts, result), List.of(), 0));
    }

    /** Như {@link #placeOnline} kèm ghi chú của khách (tối đa {@value #MAX_NOTE} ký tự, rỗng thì bỏ). */
    public static SalesOrder placeOnline(UUID tenantId, String orderNo, UUID customerUserId, Instant now,
                                         PaymentMethod method, Fulfilment fulfilment, Recipient recipient,
                                         List<LineDraft> drafts, PriceCalculator.TaxPolicy tax,
                                         UUID clientRequestId, String customerNote) {
        SalesOrder order = placeOnline(tenantId, orderNo, customerUserId, now, method, fulfilment, recipient, drafts,
                tax, clientRequestId);
        order.customerNote = note(customerNote, "Ghi chú của khách");
        return order;
    }

    /** Như {@link #createPos} (bản có đơn đổi hàng) kèm ghi chú của khách. */
    public static SalesOrder createPos(UUID tenantId, String orderNo, UUID createdBy, Instant now,
                                       PaymentMethod method, UUID customerUserId, String walkInName,
                                       String walkInPhone, List<LineDraft> drafts, Discount orderDiscount,
                                       PriceCalculator.TaxPolicy tax, UUID clientRequestId,
                                       UUID exchangeOfReturnId, String customerNote) {
        SalesOrder order = createPos(tenantId, orderNo, createdBy, now, method, customerUserId, walkInName,
                walkInPhone, drafts, orderDiscount, tax, clientRequestId, exchangeOfReturnId);
        order.customerNote = note(customerNote, "Ghi chú của khách");
        return order;
    }

    /**
     * Nhân viên đặt ghi chú nội bộ và ngày giao/nhận dự kiến (thay toàn bộ, null là xóa). Ngày dự kiến chỉ cho đơn
     * nhận tại cửa hàng hoặc giao hàng. Ghi chú của khách không đổi sau khi đặt. Ghi chú không vào audit/log.
     */
    public void updateNotes(String newStaffNote, LocalDate newExpectedDeliveryDate) {
        String note = note(newStaffNote, "Ghi chú nội bộ");
        if (newExpectedDeliveryDate != null && fulfilment == Fulfilment.COUNTER) {
            throw SalesException.invalidRequest("Ngày giao dự kiến chỉ áp dụng cho đơn nhận tại cửa hàng hoặc giao hàng");
        }
        this.staffNote = note;
        this.expectedDeliveryDate = newExpectedDeliveryDate;
    }

    /**
     * Chỉ điền các trường ghi chú/ngày giao còn trống, không ghi đè giá trị đã có; trả true nếu có thay đổi. Dùng khi
     * nạp dữ liệu demo bổ sung cho đơn đã tồn tại. Ngày giao dự kiến bị bỏ qua với đơn nhận tại quầy.
     */
    public boolean fillMissingNotes(String newCustomerNote, String newStaffNote, LocalDate newExpectedDeliveryDate) {
        boolean changed = false;
        if (customerNote == null && blankToNull(newCustomerNote) != null) {
            customerNote = note(newCustomerNote, "Ghi chú của khách");
            changed = true;
        }
        if (staffNote == null && blankToNull(newStaffNote) != null) {
            staffNote = note(newStaffNote, "Ghi chú nội bộ");
            changed = true;
        }
        if (expectedDeliveryDate == null && newExpectedDeliveryDate != null && fulfilment != Fulfilment.COUNTER) {
            expectedDeliveryDate = newExpectedDeliveryDate;
            changed = true;
        }
        return changed;
    }

    /** Cắt khoảng trắng, rỗng thành null, quá {@value #MAX_NOTE} ký tự thì INVALID_REQUEST. */
    private static String note(String value, String label) {
        String v = blankToNull(value);
        if (v != null && v.length() > MAX_NOTE) {
            throw SalesException.invalidRequest(label + " tối đa " + MAX_NOTE + " ký tự");
        }
        return v;
    }

    public static SalesOrder restore(Snapshot snapshot) {
        return new SalesOrder(snapshot);
    }

    /**
     * Đơn POS hai bước (ADR-0020, ADR-0021): AWAITING_PAYMENT + UNPAID, chưa có bản ghi thu, với MỌI phương thức POS
     * (CASH, CARD, BANK_TRANSFER thu tay; QR thu qua cổng). Hàng được giữ (reserved) bởi use case; thành COMPLETED + PAID
     * khi {@link #settleAwaitingPayment}, hoặc CANCELLED khi {@link #cancelAwaitingPayment}. Tổng tiền 0 (giảm giá hết) vẫn
     * tạo được: use case thu ngay vì không có gì để chờ.
     */
    public static SalesOrder createPosAwaitingPayment(UUID tenantId, String orderNo, UUID createdBy, Instant now,
                                                      PaymentMethod method, UUID customerUserId, String walkInName,
                                                      String walkInPhone, List<LineDraft> drafts,
                                                      Discount orderDiscount, PriceCalculator.TaxPolicy tax,
                                                      UUID clientRequestId, UUID exchangeOfReturnId,
                                                      String customerNote) {
        if (method == null || !method.allowedForPos()) {
            throw SalesException.invalidRequest("Phương thức thanh toán không hợp lệ cho bán tại quầy");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw SalesException.invalidRequest("Đơn cần ít nhất một dòng");
        }
        var result = calculate(drafts, orderDiscount, tax, Money.ZERO);
        SalesOrder order = new SalesOrder(new Snapshot(UUID.randomUUID(), tenantId, orderNo, OrderChannel.POS,
                OrderStatus.AWAITING_PAYMENT, PaymentStatus.UNPAID, method, customerUserId,
                blankToNull(walkInName), blankToNull(walkInPhone), Fulfilment.COUNTER, null, tax.vatRate().bps(),
                tax.pricesIncludeVat(), result.subtotal(), result.discountTotal(), result.vatTotal(),
                result.shippingFee(), result.grandTotal(), orderDiscount, clientRequestId, exchangeOfReturnId,
                createdBy, now, null, null, null, null, null, null, null, null, buildLines(drafts, result),
                List.of(), 0));
        order.customerNote = note(customerNote, "Ghi chú của khách");
        return order;
    }

    /** Thu xong (cổng báo hoặc thu ngân xác nhận): AWAITING_PAYMENT sang COMPLETED + PAID, ghi bản ghi thu theo phương thức của đơn. */
    public PaymentRecord settleAwaitingPayment(String reference, UUID by, Instant now) {
        requireAwaitingPayment();
        PaymentRecord p = new PaymentRecord(UUID.randomUUID(), grandTotal, paymentMethod, reference, by, now);
        payments.add(p);
        this.paymentStatus = PaymentStatus.PAID;
        this.status = OrderStatus.COMPLETED;
        this.paidAt = now;
        this.completedAt = now;
        return p;
    }

    /** Cổng QR báo đã nhận đủ tiền (người ghi: không có). */
    public PaymentRecord settleQrPayment(String reference, Instant now) {
        if (paymentMethod == null || !paymentMethod.isGateway()) {
            throw stateConflict();
        }
        return settleAwaitingPayment(reference, null, now);
    }

    /** Hết hạn hoặc thu ngân hủy khi chưa thu tiền: AWAITING_PAYMENT sang CANCELLED (use case nhả hàng giữ). */
    public void cancelAwaitingPayment(String reason, UUID by, Instant now) {
        requireAwaitingPayment();
        if (reason == null || reason.isBlank()) {
            throw SalesException.reasonRequired();
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelledBy = by;
        this.cancelReason = reason.trim();
    }

    private void requireAwaitingPayment() {
        if (channel != OrderChannel.POS || status != OrderStatus.AWAITING_PAYMENT) {
            throw stateConflict();
        }
    }

    // ---- máy trạng thái ----

    /** PENDING_CONFIRMATION sang CONFIRMED, đặt phí ship (không chịu VAT); nhận tại cửa hàng thì phí ship phải bằng 0. */
    public void confirm(Money fee, UUID by, Instant now) {
        requireOnline();
        requireStatus(OrderStatus.PENDING_CONFIRMATION);
        Money f = fee == null ? Money.ZERO : fee;
        if (fulfilment == Fulfilment.PICKUP && !f.isZero()) {
            throw SalesException.invalidRequest("Nhận tại cửa hàng không có phí ship");
        }
        this.shippingFee = f;
        this.grandTotal = f.plus(sumLineTotals());
        this.status = OrderStatus.CONFIRMED;
        this.confirmedAt = now;
        this.confirmedBy = by;
    }

    /** Đánh dấu đã thu: chỉ khi CONFIRMED, READY_FOR_PICKUP hoặc SHIPPING và đang UNPAID; số tiền = grandTotal. */
    public PaymentRecord markPaid(PaymentMethod method, String reference, UUID by, Instant now) {
        requireOnline();
        if (status != OrderStatus.CONFIRMED && status != OrderStatus.READY_FOR_PICKUP
                && status != OrderStatus.SHIPPING) {
            throw stateConflict();
        }
        if (paymentStatus != PaymentStatus.UNPAID) {
            throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Đơn đã được đánh dấu đã thu");
        }
        if (method == null || method.isGateway()) {
            // QR/MoMo chỉ được ghi nhận qua cổng thanh toán (settleQrPayment), không đánh dấu tay
            throw SalesException.invalidRequest("Thiếu hoặc sai phương thức thanh toán");
        }
        PaymentRecord p = new PaymentRecord(UUID.randomUUID(), grandTotal, method,
                reference == null || reference.isBlank() ? null : reference.trim(), by, now);
        payments.add(p);
        this.paymentStatus = PaymentStatus.PAID;
        this.paidAt = now;
        return p;
    }

    public void markReadyForPickup() {
        requireOnline();
        requireStatus(OrderStatus.CONFIRMED);
        if (fulfilment != Fulfilment.PICKUP) {
            throw stateConflict();
        }
        this.status = OrderStatus.READY_FOR_PICKUP;
    }

    /** Hàng rời cửa hàng: use case FULFIL tồn kho (onHand và reserved cùng giảm). */
    public void markShipping() {
        requireOnline();
        requireStatus(OrderStatus.CONFIRMED);
        if (fulfilment != Fulfilment.DELIVERY) {
            throw stateConflict();
        }
        this.status = OrderStatus.SHIPPING;
    }

    /** READY_FOR_PICKUP hoặc SHIPPING sang COMPLETED; cần PAID. Nhận tại quầy: use case FULFIL tồn kho. */
    public void complete(Instant now) {
        requireOnline();
        if (status != OrderStatus.READY_FOR_PICKUP && status != OrderStatus.SHIPPING) {
            throw stateConflict();
        }
        if (paymentStatus != PaymentStatus.PAID) {
            throw new SalesException(ErrorCode.ORDER_NOT_PAID, "Đơn chưa thu tiền");
        }
        this.status = OrderStatus.COMPLETED;
        this.completedAt = now;
    }

    /**
     * Hủy đơn online. Khách chỉ hủy được khi PENDING_CONFIRMATION; nhân viên hủy được trước COMPLETED (kể cả giao
     * thất bại ở SHIPPING). Lý do bắt buộc. Trả về hệ quả tồn kho: RELEASE (đang giữ) hoặc RESTOCK (đã rời kho).
     */
    public StockEffect cancel(String reason, UUID by, Instant now, boolean byCustomer) {
        requireOnline();
        if (reason == null || reason.isBlank()) {
            throw SalesException.reasonRequired();
        }
        boolean allowed = byCustomer ? status == OrderStatus.PENDING_CONFIRMATION
                : status == OrderStatus.PENDING_CONFIRMATION || status == OrderStatus.CONFIRMED
                || status == OrderStatus.READY_FOR_PICKUP || status == OrderStatus.SHIPPING;
        if (!allowed) {
            throw stateConflict();
        }
        StockEffect effect = status == OrderStatus.SHIPPING ? StockEffect.RESTOCK : StockEffect.RELEASE;
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelledBy = by;
        this.cancelReason = reason.trim();
        return effect;
    }

    /** Số tiền phải hoàn khi hủy: đơn đã thu thì hoàn toàn bộ grandTotal, chưa thu thì không có hoàn tiền (null). */
    public Money refundDueOnCancel() {
        return status == OrderStatus.CANCELLED && paymentStatus == PaymentStatus.PAID && !grandTotal.isZero()
                ? grandTotal : null;
    }

    /**
     * Giảm giá cho đơn online (chỉ UNPAID, PENDING_CONFIRMATION hoặc CONFIRMED): mỗi lần gọi thay toàn bộ giảm giá
     * và tính lại mọi dòng. {@code lineDiscounts} theo lineNo; thiếu thì dòng không giảm.
     */
    public void applyDiscount(Discount newOrderDiscount, Map<Integer, Discount> lineDiscounts) {
        requireOnline();
        if (paymentStatus != PaymentStatus.UNPAID
                || (status != OrderStatus.PENDING_CONFIRMATION && status != OrderStatus.CONFIRMED)) {
            throw stateConflict();
        }
        List<LineDraft> drafts = new ArrayList<>();
        for (OrderLine l : lines) {
            Discount d = lineDiscounts == null ? null : lineDiscounts.get(l.lineNo());
            drafts.add(new LineDraft(l.titleId(), l.titleName(), l.isbn(), l.unitPrice(), l.quantity(), d));
        }
        var result = calculate(drafts, newOrderDiscount,
                new PriceCalculator.TaxPolicy(lms.sales.shared.domain.Rate.ofBps(vatRateBps), pricesIncludeVat),
                shippingFee);
        List<OrderLine> rebuilt = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            OrderLine l = lines.get(i);
            var p = result.lines().get(i);
            rebuilt.add(new OrderLine(l.id(), l.lineNo(), l.titleId(), l.titleName(), l.isbn(), l.quantity(),
                    l.unitPrice(), p.lineDiscount(), p.allocatedOrderDiscount(), p.amount(), p.vatAmount(),
                    p.total()));
        }
        this.lines = List.copyOf(rebuilt);
        this.orderDiscount = newOrderDiscount;
        this.subtotal = result.subtotal();
        this.discountTotal = result.discountTotal();
        this.vatTotal = result.vatTotal();
        this.grandTotal = result.grandTotal();
        checkTotal();
    }

    /**
     * Cập nhật trục thanh toán theo tổng đã hoàn (khoản hoàn REFUNDED): hoàn đủ tiền hàng thì REFUNDED, còn lại
     * PARTIALLY_REFUNDED. Chỉ áp cho đơn đã thu.
     */
    public void applyRefundedTotal(Money refundedTotal) {
        if (paymentStatus == PaymentStatus.UNPAID || refundedTotal.isZero()) {
            return;
        }
        this.paymentStatus = refundedTotal.amount() >= sumLineTotals().amount() ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
    }

    private Money sumLineTotals() {
        long sum = 0;
        for (OrderLine l : lines) {
            sum += l.lineTotal().amount();
        }
        return Money.of(sum);
    }

    private void requireOnline() {
        if (channel != OrderChannel.ONLINE) {
            throw stateConflict();
        }
    }

    /** Trạng thái đơn phải đúng giá trị mong đợi, nếu không 409 ORDER_STATE_CONFLICT. */
    public void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw stateConflict();
        }
    }

    private static SalesException stateConflict() {
        return new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Trạng thái đơn không cho phép thao tác");
    }

    private static PriceCalculator.Result calculate(List<LineDraft> drafts, Discount orderDiscount,
                                                    PriceCalculator.TaxPolicy tax, Money shippingFee) {
        var inputs = drafts.stream()
                .map(d -> new PriceCalculator.LineInput(d.unitPrice(), d.quantity(), d.lineDiscount())).toList();
        return PriceCalculator.calculate(inputs, orderDiscount, tax, shippingFee);
    }

    private static List<OrderLine> buildLines(List<LineDraft> drafts, PriceCalculator.Result result) {
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            var d = drafts.get(i);
            var p = result.lines().get(i);
            lines.add(new OrderLine(UUID.randomUUID(), i + 1, d.titleId(), d.titleName(), d.isbn(), d.quantity(),
                    d.unitPrice(), p.lineDiscount(), p.allocatedOrderDiscount(), p.amount(), p.vatAmount(),
                    p.total()));
        }
        return lines;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String orderNo() { return orderNo; }
    public OrderChannel channel() { return channel; }
    public OrderStatus status() { return status; }
    public PaymentStatus paymentStatus() { return paymentStatus; }
    public PaymentMethod paymentMethod() { return paymentMethod; }
    public UUID customerUserId() { return customerUserId; }
    public String walkInName() { return walkInName; }
    public String walkInPhone() { return walkInPhone; }
    public Fulfilment fulfilment() { return fulfilment; }
    public Recipient recipient() { return recipient; }
    public int vatRateBps() { return vatRateBps; }
    public boolean pricesIncludeVat() { return pricesIncludeVat; }
    public Money subtotal() { return subtotal; }
    public Money discountTotal() { return discountTotal; }
    public Money vatTotal() { return vatTotal; }
    public Money shippingFee() { return shippingFee; }
    public Money grandTotal() { return grandTotal; }
    public Discount orderDiscount() { return orderDiscount; }
    public UUID clientRequestId() { return clientRequestId; }
    public UUID exchangeOfReturnId() { return exchangeOfReturnId; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Instant confirmedAt() { return confirmedAt; }
    public UUID confirmedBy() { return confirmedBy; }
    public Instant paidAt() { return paidAt; }
    public Instant completedAt() { return completedAt; }
    public Instant cancelledAt() { return cancelledAt; }
    public UUID cancelledBy() { return cancelledBy; }
    public String cancelReason() { return cancelReason; }
    public Instant piiAnonymizedAt() { return piiAnonymizedAt; }
    public String customerNote() { return customerNote; }
    /** Ghi chú nội bộ: chỉ qua endpoint khóa riêng của nhân viên, không bao giờ ra storefront/log/audit. */
    public String staffNote() { return staffNote; }
    public LocalDate expectedDeliveryDate() { return expectedDeliveryDate; }
    public List<OrderLine> lines() { return lines; }
    public List<PaymentRecord> payments() { return List.copyOf(payments); }
    public long version() { return version; }

    /** Không in dữ liệu cá nhân (tên/điện thoại khách vãng lai, người nhận). */
    @Override
    public String toString() {
        return "SalesOrder[" + orderNo + ", " + status + ", " + paymentStatus + ", total=" + grandTotal.amount() + "]";
    }
}
