package lms.sales.ordering.infrastructure.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.OrderPlan;
import lms.demo.plan.DemoPlan.RefundPlan;
import lms.demo.plan.DemoPlan.SalesReturnPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.ordering.application.RefundRepository;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.OrderChannel;
import lms.sales.ordering.domain.OrderLine;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentRecord;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.PriceCalculator;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.RefundSource;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.ordering.domain.ReturnLine;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.ordering.domain.SalesReturn;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rate;
import lms.sales.shared.infrastructure.demo.DemoTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed đơn hàng của tenant demo (ADR-0018): đơn POS/online đủ trạng thái, bản ghi thu, phiếu trả hàng và khoản hoàn.
 * <p>
 * Idempotent theo id tất định của kế hoạch (số đơn {@code DEMO-0001...} cũng là khóa tự nhiên): đơn/phiếu/khoản hoàn
 * đã có thì bỏ qua, chạy lại không nhân đôi. Mỗi đơn (kèm dòng, bản ghi thu) là một transaction; không phát event, không
 * ghi audit (dữ liệu mẫu). Tiền tính bằng {@link PriceCalculator} rồi hiệu chỉnh VAT dòng cuối để tổng khớp đúng kế
 * hoạch. Mốc thời gian từ {@link DemoTimes}: không mốc nào vượt hiện tại, {@code paid_at} chỉ có ở đơn đã thanh toán,
 * đơn thanh toán rồi hủy giữ nguyên {@code paid_at}.
 * <p>
 * Tồn kho do bước stock ghi (không đụng ở đây). Dữ liệu cá nhân (người nhận) là dữ liệu giả.
 */
@Component
@Profile("demo-seed")
public class OrderingDemoSeedStep implements DemoSeedStep {

    private static final PaymentMethod[] POS_METHODS = {PaymentMethod.CASH, PaymentMethod.CARD,
            PaymentMethod.BANK_TRANSFER};

    private final SalesOrderRepository orders;
    private final SalesReturnRepository returns;
    private final RefundRepository refunds;
    private final TenantDirectoryPort tenants;
    private final TransactionOperations tx;

    public OrderingDemoSeedStep(SalesOrderRepository orders, SalesReturnRepository returns, RefundRepository refunds,
                                TenantDirectoryPort tenants, TransactionOperations tx) {
        this.orders = orders;
        this.returns = returns;
        this.refunds = refunds;
        this.tenants = tenants;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public String name() {
        return "sales.ordering";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        UUID tenantId = context.tenantId();
        DemoPlan plan = context.plan();
        Map<UUID, String> isbnByTitle = new HashMap<>();
        plan.titles().forEach(t -> isbnByTitle.put(t.id(), t.isbn()));
        int created = 0;
        int skipped = 0;

        for (OrderPlan o : plan.orders()) {
            var existing = orders.findById(tenantId, o.id());
            if (existing.isPresent()) {
                // Đã có đơn: chỉ điền ghi chú/ngày giao còn trống (chạy lại không đổi gì thì bỏ qua)
                boolean updated = Boolean.TRUE.equals(tx.execute(status -> {
                    SalesOrder cur = orders.findById(tenantId, o.id()).orElseThrow();
                    if (!cur.fillMissingNotes(o.customerNote(), o.staffNote(), expectedDelivery(plan, o))) {
                        return false;
                    }
                    orders.save(cur);
                    return true;
                }));
                if (updated) {
                    created++;
                } else {
                    skipped++;
                }
                continue;
            }
            tx.executeWithoutResult(status -> {
                // danh bạ tenant cho job ẩn danh (như luồng tạo đơn thật); cần transaction đang mở
                tenants.touch(tenantId);
                orders.save(build(tenantId, plan, o, isbnByTitle, context.clock()));
            });
            created++;
        }

        for (SalesReturnPlan r : plan.salesReturns()) {
            if (returns.findById(tenantId, r.id()).isPresent()) {
                skipped++;
                continue;
            }
            tx.executeWithoutResult(status -> returns.save(buildReturn(tenantId, plan, r, context.clock())));
            created++;
        }

        for (RefundPlan r : plan.refunds()) {
            if (refunds.findById(tenantId, r.id()).isPresent()) {
                skipped++;
                continue;
            }
            tx.executeWithoutResult(status -> refunds.save(buildRefund(tenantId, plan, r, context.clock())));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    // ---- Đơn hàng ----

    /** Mốc thời gian vòng đời của một đơn (đã kẹp không vượt hiện tại). */
    private record Lifecycle(Instant created, Instant confirmed, Instant paid, Instant completed, Instant cancelled) {
    }

    private static Lifecycle lifecycle(DemoPlan plan, OrderPlan o, Clock clock) {
        Instant created = DemoTimes.orderCreatedAt(o, plan, clock);
        boolean online = o.channel() == DemoPlan.Channel.ONLINE;
        Instant confirmed = null;
        Instant paid = null;
        Instant completed = null;
        Instant cancelled = null;
        if (!online) {
            paid = created;
            completed = created;
            return new Lifecycle(created, null, paid, completed, null);
        }
        boolean cancelledUnpaid = o.status() == DemoPlan.OrderStatus.CANCELLED && !o.isPaid();
        if (o.status() != DemoPlan.OrderStatus.PENDING_CONFIRMATION && !cancelledUnpaid) {
            confirmed = DemoTimes.plusClamped(created, Duration.ofMinutes(20), clock);
        }
        if (o.isPaid()) {
            paid = DemoTimes.plusClamped(created, Duration.ofMinutes(30), clock);
        }
        if (o.status() == DemoPlan.OrderStatus.COMPLETED) {
            completed = DemoTimes.plusClamped(created, Duration.ofHours(2), clock);
        }
        if (o.status() == DemoPlan.OrderStatus.CANCELLED) {
            cancelled = DemoTimes.plusClamped(created, Duration.ofMinutes(90), clock);
        }
        return new Lifecycle(created, confirmed, paid, completed, cancelled);
    }

    private SalesOrder build(UUID tenantId, DemoPlan plan, OrderPlan o, Map<UUID, String> isbnByTitle, Clock clock) {
        Lifecycle t = lifecycle(plan, o, clock);
        boolean online = o.channel() == DemoPlan.Channel.ONLINE;
        OrderStatus status = OrderStatus.valueOf(o.status().name());
        Fulfilment fulfilment = !online ? Fulfilment.COUNTER
                : status == OrderStatus.READY_FOR_PICKUP ? Fulfilment.PICKUP : Fulfilment.DELIVERY;
        PaymentMethod method = !online ? POS_METHODS[o.index() % POS_METHODS.length]
                : fulfilment == Fulfilment.PICKUP || o.index() % 2 == 1 ? PaymentMethod.BANK_TRANSFER
                : PaymentMethod.COD;
        UUID staff = plan.staff().get(2).id();
        UUID customer = online ? plan.readers().get(o.index() % plan.readers().size()).id() : null;
        Recipient recipient = online ? new Recipient(plan.readers().get(o.index() % plan.readers().size()).fullName(),
                String.format("0901%06d", 100_000 + o.index()), "Số " + (20 + o.index()) + " đường Demo, Hà Nội")
                : null;

        // Tính tiền bằng PriceCalculator (giá chưa gồm VAT 10%), rồi dồn chênh lệch làm tròn vào VAT dòng cuối
        List<PriceCalculator.LineInput> inputs = o.lines().stream().map(l -> new PriceCalculator.LineInput(
                Money.of(l.unitPrice()), l.quantity(), null)).toList();
        Discount orderDiscount = o.discount() > 0 ? Discount.amount(o.discount()) : null;
        PriceCalculator.Result calc = PriceCalculator.calculate(inputs, orderDiscount,
                new PriceCalculator.TaxPolicy(Rate.ofBps(DemoTimes.VAT_RATE_BPS), DemoTimes.PRICES_INCLUDE_VAT),
                Money.of(o.shippingFee()));
        long vatDelta = o.vat() - calc.vatTotal().amount();
        List<OrderLine> lines = new ArrayList<>();
        for (int i = 0; i < o.lines().size(); i++) {
            DemoPlan.OrderLinePlan l = o.lines().get(i);
            PriceCalculator.LinePrice p = calc.lines().get(i);
            long adjust = i == o.lines().size() - 1 ? vatDelta : 0;
            lines.add(new OrderLine(DemoIds.of("order-line:" + o.index(), i + 1), i + 1, l.titleId(), l.titleName(),
                    isbnByTitle.get(l.titleId()), l.quantity(), Money.of(l.unitPrice()), p.lineDiscount(),
                    p.allocatedOrderDiscount(), p.amount(), Money.of(p.vatAmount().amount() + adjust),
                    Money.of(p.total().amount() + adjust)));
        }
        long lineTotals = lines.stream().mapToLong(l -> l.lineTotal().amount()).sum();
        if (calc.subtotal().amount() != o.subtotal() || lineTotals + o.shippingFee() != o.total()) {
            throw new IllegalStateException("Tiền đơn " + o.orderNo() + " không khớp kế hoạch");
        }

        List<PaymentRecord> payments = new ArrayList<>();
        if (o.isPaid()) {
            payments.add(new PaymentRecord(DemoIds.of("payment", o.index()), Money.of(o.total()), method, null,
                    staff, t.paid()));
        }
        String cancelReason = t.cancelled() != null ? "Khách yêu cầu hủy đơn (dữ liệu demo)" : null;
        return SalesOrder.restore(new SalesOrder.Snapshot(o.id(), tenantId, o.orderNo(),
                OrderChannel.valueOf(o.channel().name()), status, PaymentStatus.valueOf(o.paymentStatus().name()),
                method, customer, null, null, fulfilment, recipient, DemoTimes.VAT_RATE_BPS,
                DemoTimes.PRICES_INCLUDE_VAT, Money.of(o.subtotal()), Money.of(o.discount()), Money.of(o.vat()),
                Money.of(o.shippingFee()), Money.of(o.total()), orderDiscount,
                DemoIds.of("request:order", o.index()), null, online ? customer : staff, t.created(), t.confirmed(),
                t.confirmed() != null ? staff : null, t.paid(), t.completed(), t.cancelled(),
                t.cancelled() != null ? customer : null, cancelReason, null, lines, payments, 0, o.customerNote(),
                o.staffNote(), fulfilment == Fulfilment.COUNTER ? null : expectedDelivery(plan, o)));
    }

    /** Ngày giao/nhận dự kiến = ngày tạo đơn + số ngày của kế hoạch; null nếu kế hoạch không có. */
    private static java.time.LocalDate expectedDelivery(DemoPlan plan, OrderPlan o) {
        return o.expectedDeliveryDays() == null ? null
                : plan.today().minusDays(o.createdDaysAgo()).plusDays(o.expectedDeliveryDays());
    }

    // ---- Phiếu trả hàng và hoàn tiền ----

    private SalesReturn buildReturn(UUID tenantId, DemoPlan plan, SalesReturnPlan r, Clock clock) {
        OrderPlan orderPlan = plan.orders().get(r.orderIndex());
        SalesOrder order = orders.findById(tenantId, orderPlan.id()).orElseThrow(() -> new IllegalStateException(
                "Chưa có đơn " + orderPlan.orderNo() + " để tạo phiếu trả"));
        OrderLine line = order.lines().stream().filter(l -> l.titleId().equals(r.titleId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Đơn " + orderPlan.orderNo() + " không có đầu sách trả"));
        RefundPlan refund = plan.refunds().stream().filter(x -> x.returnIndex() != null && x.returnIndex() == r.index())
                .findFirst().orElseThrow();
        List<ReturnLine> lines = List.of(new ReturnLine(DemoIds.of("sales-return-line", r.index()), line.id(),
                line.titleId(), r.quantity(), Disposition.RESTOCK, Money.of(refund.amount())));
        return new SalesReturn(r.id(), tenantId, String.format("DEMO-TH-%04d", r.index() + 1), order.id(),
                "Sách bị lỗi in ấn (dữ liệu demo)", false, null, 0, DemoIds.of("request:sales-return", r.index()),
                plan.staff().get(2).id(), DemoTimes.returnCreatedAt(r, plan, clock), lines);
    }

    private Refund buildRefund(UUID tenantId, DemoPlan plan, RefundPlan r, Clock clock) {
        OrderPlan orderPlan = plan.orders().get(r.orderIndex());
        boolean done = r.status() == DemoPlan.RefundStatus.REFUNDED;
        RefundSource source = RefundSource.valueOf(r.source().name());
        Instant createdAt;
        UUID returnId = null;
        PaymentMethod method = PaymentMethod.CASH;
        if (source == RefundSource.RETURN) {
            SalesReturnPlan ret = plan.salesReturns().get(r.returnIndex());
            returnId = ret.id();
            createdAt = DemoTimes.returnCreatedAt(ret, plan, clock);
        } else {
            createdAt = lifecycle(plan, orderPlan, clock).cancelled();
            if (createdAt == null) {
                throw new IllegalStateException("Đơn " + orderPlan.orderNo() + " chưa hủy nhưng có hoàn do hủy");
            }
            method = PaymentMethod.BANK_TRANSFER;
        }
        Instant refundedAt = done ? DemoTimes.plusClamped(createdAt, Duration.ofHours(1), clock) : null;
        return Refund.restore(r.id(), tenantId, orderPlan.id(), source, returnId, Money.of(r.amount()),
                RefundStatus.valueOf(r.status().name()), done ? method : null, null, createdAt,
                done ? plan.staff().get(2).id() : null, refundedAt, 0);
    }
}
