package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentRecord;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.ordering.domain.StockEffect;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.StockLine;
import lms.sales.stock.application.StockService;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thao tác nhân viên trên đơn online (xác nhận, giảm giá, đánh dấu thu, nhận tại quầy/giao, hoàn tất, hủy). Mỗi thao
 * tác một transaction của sales_db: đơn (khóa lạc quan @Version) + tồn kho + sổ cái + khoản hoàn + audit. Đơn được
 * lưu TRƯỚC khi đụng tồn để xung đột phiên bản (hai người thao tác cùng lúc) báo 409 ORDER_STATE_CONFLICT sớm; thua
 * cuộc thì rollback cả tồn lẫn audit. {@code expectedVersion} (nếu gửi) lệch thì cũng 409.
 */
public class OrderWorkflowUseCases {

    private final SalesOrderRepository orders;
    private final RefundRepository refunds;
    private final StockService stockService;
    private final GetSalesSettingsUseCase settings;
    private final SalesAuditPort audit;
    private final Clock clock;

    public OrderWorkflowUseCases(SalesOrderRepository orders, RefundRepository refunds, StockService stockService,
                                 GetSalesSettingsUseCase settings, SalesAuditPort audit, Clock clock) {
        this.orders = orders;
        this.refunds = refunds;
        this.stockService = stockService;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** Xác nhận đơn; phí ship không gửi thì dùng phí cố định của tenant khi giao hàng, 0 khi nhận tại cửa hàng. */
    @Transactional
    public OrderResponse confirm(UUID tenantId, UUID actor, UUID orderId, Long shippingFee, Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        Money fee;
        if (shippingFee != null) {
            fee = Money.of(shippingFee);
        } else if (o.paymentStatus() == lms.sales.ordering.domain.PaymentStatus.PAID) {
            fee = o.shippingFee(); // đã trả trước (ADR-0029): phí ship đã chốt lúc đặt, không áp phí cố định lần nữa
        } else if (o.fulfilment() == lms.sales.ordering.domain.Fulfilment.DELIVERY) {
            fee = settings.execute(tenantId).fixedShippingMoney();
        } else {
            fee = Money.ZERO;
        }
        Instant now = clock.instant();
        o.confirm(fee, actor, now);
        SalesOrder saved = orders.save(o);
        record(saved, SalesAuditPort.Actions.ORDER_CONFIRMED, actor, now,
                Map.of("shippingFee", saved.shippingFee().amount(), "grandTotal", saved.grandTotal().amount()));
        return OrderResponse.from(saved);
    }

    /** Giảm giá đơn online (khóa riêng); mỗi lần gọi thay toàn bộ giảm giá. */
    @Transactional
    public OrderResponse applyDiscount(UUID tenantId, UUID actor, UUID orderId, Discount orderDiscount,
                                       Map<Integer, Discount> lineDiscounts, Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        Instant now = clock.instant();
        o.applyDiscount(orderDiscount, lineDiscounts == null ? Map.of() : new HashMap<>(lineDiscounts));
        SalesOrder saved = orders.save(o);
        record(saved, SalesAuditPort.Actions.ORDER_DISCOUNT_APPLIED, actor, now,
                Map.of("discountTotal", saved.discountTotal().amount(), "subtotal", saved.subtotal().amount(),
                        "grandTotal", saved.grandTotal().amount()));
        return OrderResponse.from(saved);
    }

    /** Đánh dấu đã thu (chuyển khoản sau đối soát, COD sau khi nhận tiền): ghi người, thời điểm, số tiền. */
    @Transactional
    public OrderResponse markPaid(UUID tenantId, UUID actor, UUID orderId, PaymentMethod method, String reference,
                                  Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        Instant now = clock.instant();
        PaymentRecord p = o.markPaid(method, reference, actor, now);
        SalesOrder saved = orders.save(o);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amount", p.amount().amount());
        data.put("method", p.method().name());
        if (p.reference() != null) {
            data.put("reference", p.reference());
        }
        record(saved, SalesAuditPort.Actions.ORDER_PAID, actor, now, data);
        return OrderResponse.from(saved);
    }

    @Transactional
    public OrderResponse markReadyForPickup(UUID tenantId, UUID actor, UUID orderId, Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        o.markReadyForPickup();
        SalesOrder saved = orders.save(o);
        record(saved, SalesAuditPort.Actions.ORDER_READY_FOR_PICKUP, actor, clock.instant(), Map.of());
        return OrderResponse.from(saved);
    }

    /** Giao hàng tự quản: hàng rời cửa hàng nên FULFIL tồn (onHand và reserved cùng giảm). */
    @Transactional
    public OrderResponse markShipping(UUID tenantId, UUID actor, UUID orderId, Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        o.markShipping();
        SalesOrder saved = orders.save(o);
        stockService.fulfilAll(tenantId, actor, stockLines(saved), "ORDER", saved.id());
        record(saved, SalesAuditPort.Actions.ORDER_SHIPPING, actor, clock.instant(), Map.of());
        return OrderResponse.from(saved);
    }

    /** Hoàn tất (cần PAID). Nhận tại quầy: FULFIL tồn lúc này; giao hàng đã FULFIL từ lúc SHIPPING. */
    @Transactional
    public OrderResponse complete(UUID tenantId, UUID actor, UUID orderId, Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        OrderStatus before = o.status();
        Instant now = clock.instant();
        o.complete(now);
        SalesOrder saved = orders.save(o);
        if (before == OrderStatus.READY_FOR_PICKUP) {
            stockService.fulfilAll(tenantId, actor, stockLines(saved), "ORDER", saved.id());
        }
        record(saved, SalesAuditPort.Actions.ORDER_COMPLETED, actor, now, Map.of());
        return OrderResponse.from(saved);
    }

    /**
     * Nhân viên hủy (kể cả đơn chuyển khoản quá hạn chưa thu và giao thất bại): nhả hàng giữ hoặc nhập lại hàng đã
     * rời kho; đơn đã thu thì sinh khoản hoàn chờ hoàn trong cùng transaction.
     */
    @Transactional
    public OrderResponse cancel(UUID tenantId, UUID actor, UUID orderId, String reason, Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        return cancelLoaded(tenantId, actor, o, reason, false);
    }

    /**
     * Khách tự hủy đơn của chính mình (một transaction): đơn của người khác 404. Domain chỉ cho hủy khi
     * PENDING_CONFIRMATION và UNPAID; đơn AWAITING_PAYMENT đi luồng hủy lệnh ({@link ShopPaymentUseCases}).
     */
    @Transactional
    public ShopOrderResponse cancelOwnOrder(UUID tenantId, UUID customerUserId, UUID orderId, String reason) {
        SalesOrder o = orders.findByIdAndCustomer(tenantId, orderId, customerUserId)
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        OrderResponse r = cancelLoaded(tenantId, customerUserId, o, reason, true);
        return new ShopOrderResponse(r, ShopOrderResponse.from(o).recipient());
    }

    /** Dùng chung với khách tự hủy (ShopOrderUseCases): cùng hệ quả tồn kho, khoản hoàn và audit. */
    public OrderResponse cancelLoaded(UUID tenantId, UUID actor, SalesOrder o, String reason, boolean byCustomer) {
        Instant now = clock.instant();
        StockEffect effect = o.cancel(reason, actor, now, byCustomer);
        SalesOrder saved = orders.save(o);
        if (effect == StockEffect.RESTOCK) {
            stockService.cancelRestockAll(tenantId, actor, stockLines(saved), "ORDER", saved.id());
        } else {
            stockService.releaseAll(tenantId, actor, stockLines(saved), "ORDER", saved.id());
        }
        Money due = saved.refundDueOnCancel();
        if (due != null) {
            Refund refund = refunds.save(Refund.forCancellation(tenantId, saved.id(), due, now));
            audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.REFUND_CREATED, "Refund",
                    refund.id(), actor, now, Map.of("orderId", saved.id().toString(), "source", "CANCELLATION",
                    "amount", due.amount())));
        }
        record(saved, SalesAuditPort.Actions.ORDER_CANCELLED, actor, now,
                Map.of("stockEffect", effect.name(), "byCustomer", byCustomer,
                        "refundDue", due == null ? 0L : due.amount()));
        return OrderResponse.from(saved);
    }

    /** Ghi chú của đơn gồm ghi chú nội bộ (staffNote): chỉ qua use case/endpoint khóa riêng, không vào log/audit. */
    public record OrderNotes(UUID orderId, String customerNote, String staffNote,
                             java.time.LocalDate expectedDeliveryDate, long version) {
        static OrderNotes of(SalesOrder o) {
            return new OrderNotes(o.id(), o.customerNote(), o.staffNote(), o.expectedDeliveryDate(), o.version());
        }

        @Override
        public String toString() {
            return "OrderNotes[order=" + orderId + "]";
        }
    }

    @Transactional(readOnly = true)
    public OrderNotes notes(UUID tenantId, UUID orderId) {
        return OrderNotes.of(load(tenantId, orderId, null));
    }

    /**
     * Đặt ghi chú nội bộ và ngày giao/nhận dự kiến (thay toàn bộ; null là xóa). Audit chỉ ghi có/không, không ghi nội
     * dung. {@code expectedVersion} lệch thì 409 ORDER_STATE_CONFLICT.
     */
    @Transactional
    public OrderNotes updateNotes(UUID tenantId, UUID actor, UUID orderId, String staffNote,
                                  java.time.LocalDate expectedDeliveryDate, Long expectedVersion) {
        SalesOrder o = load(tenantId, orderId, expectedVersion);
        Instant now = clock.instant();
        o.updateNotes(staffNote, expectedDeliveryDate);
        SalesOrder saved = orders.save(o);
        record(saved, SalesAuditPort.Actions.ORDER_NOTES_UPDATED, actor, now,
                Map.of("hasStaffNote", saved.staffNote() != null,
                        "hasExpectedDeliveryDate", saved.expectedDeliveryDate() != null));
        return OrderNotes.of(saved);
    }

    private SalesOrder load(UUID tenantId, UUID orderId, Long expectedVersion) {
        SalesOrder o = orders.findById(tenantId, orderId)
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        if (expectedVersion != null && expectedVersion != o.version()) {
            throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Đơn hàng đã bị thay đổi bởi thao tác khác");
        }
        return o;
    }

    static List<StockLine> stockLines(SalesOrder o) {
        return o.lines().stream().map(l -> new StockLine(l.titleId(), l.quantity())).toList();
    }

    private void record(SalesOrder o, String action, UUID actor, Instant now, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("orderNo", o.orderNo());
        data.put("status", o.status().name());
        data.put("paymentStatus", o.paymentStatus().name());
        audit.record(new SalesAuditPort.AuditEntry(o.tenantId(), action, "SalesOrder", o.id(), actor, now, data));
    }
}
