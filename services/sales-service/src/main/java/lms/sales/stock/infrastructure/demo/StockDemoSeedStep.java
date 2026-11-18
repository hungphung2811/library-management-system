package lms.sales.stock.infrastructure.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.OrderLinePlan;
import lms.demo.plan.DemoPlan.OrderPlan;
import lms.demo.plan.DemoPlan.OrderStatus;
import lms.demo.plan.DemoPlan.ReceiptPlan;
import lms.demo.plan.DemoPlan.SalesReturnPlan;
import lms.demo.plan.DemoPlan.StockItemPlan;
import lms.demo.plan.DemoPlan.SupplierPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.shared.domain.Money;
import lms.sales.shared.infrastructure.demo.DemoTimes;
import lms.sales.stock.application.GoodsReceiptRepository;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.GoodsReceiptLine;
import lms.sales.stock.domain.MovementType;
import lms.sales.stock.domain.ReceiptStatus;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockItemDetails;
import lms.sales.stock.domain.StockMovement;
import lms.sales.stock.domain.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed tồn kho của tenant demo (ADR-0018): nhà cung cấp, phiếu nhập đã xác nhận, mặt hàng tồn và sổ cái.
 * <p>
 * Mỗi mặt hàng được chèn cùng sổ cái trong MỘT transaction (có mặt hàng nghĩa là đã có đủ sổ cái, nên idempotent theo
 * mặt hàng). Sổ cái dựng từ kế hoạch: RECEIPT theo dòng phiếu nhập, SALE theo từng dòng của đơn không bị hủy, RETURN
 * theo phiếu trả, RESERVE cho phần đang giữ; tổng delta bằng đúng onHand/reserved của kế hoạch (kiểm lại trước khi
 * ghi, lệch thì dừng). Chỉ dùng port và domain của context stock, không đụng ordering.
 * <p>
 * Lưu ý: kế hoạch coi mọi đơn chưa hủy là đã trừ kho (kể cả đơn online đang chờ xác nhận), nên các đơn đó được ghi
 * SALE thay vì RESERVE.
 */
@Component
@Profile("demo-seed")
public class StockDemoSeedStep implements DemoSeedStep {

    private static final int RECEIPT_MINUTE_OF_DAY = 540;

    private final SupplierRepository suppliers;
    private final GoodsReceiptRepository receipts;
    private final StockPort stock;
    private final TransactionOperations tx;

    public StockDemoSeedStep(SupplierRepository suppliers, GoodsReceiptRepository receipts, StockPort stock,
                             TransactionOperations tx) {
        this.suppliers = suppliers;
        this.receipts = receipts;
        this.stock = stock;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String name() {
        return "sales.stock";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        UUID tenantId = context.tenantId();
        DemoPlan plan = context.plan();
        int created = 0;
        int skipped = 0;

        for (SupplierPlan s : plan.suppliers()) {
            if (suppliers.findById(tenantId, s.id()).isPresent() || suppliers.existsByName(tenantId, s.name(), null)) {
                skipped++;
                continue;
            }
            tx.executeWithoutResult(status -> suppliers.save(Supplier.restore(s.id(), tenantId, s.name(),
                    phone(s.index()), s.email(), "Số " + (10 + s.index()) + " đường Demo, Hà Nội",
                    "Dữ liệu demo (" + s.contactName() + ")", true)));
            created++;
        }

        UUID actor = plan.staff().get(2).id();
        for (ReceiptPlan r : plan.receipts()) {
            if (receipts.findById(tenantId, r.id()).isPresent()) {
                skipped++;
                continue;
            }
            tx.executeWithoutResult(status -> createReceipt(tenantId, plan, r, actor, context));
            created++;
        }

        for (StockItemPlan item : plan.stockItems()) {
            if (stock.find(tenantId, item.titleId()).isPresent()) {
                // Đã có mặt hàng: chỉ bổ sung chi tiết quản lý còn trống (chạy lại không đổi gì thì bỏ qua)
                if (Boolean.TRUE.equals(tx.execute(status -> topUpDetails(tenantId, plan, item, context)))) {
                    created++;
                } else {
                    skipped++;
                }
                continue;
            }
            tx.executeWithoutResult(status -> createItem(tenantId, plan, item, actor, context));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    private void createReceipt(UUID tenantId, DemoPlan plan, ReceiptPlan r, UUID actor, DemoSeedContext context) {
        Instant confirmedAt = receiptTime(plan, r, context);
        List<GoodsReceiptLine> lines = r.lines().stream()
                .map(l -> new GoodsReceiptLine(l.titleId(), l.quantity(), Money.of(l.unitCost()))).toList();
        GoodsReceipt draft = GoodsReceipt.restore(r.id(), tenantId, String.format("DEMO-NK-%04d", r.index() + 1),
                plan.suppliers().get(r.supplierIndex()).id(), lines, DemoIds.of("request:receipt", r.index()), actor,
                confirmedAt.minus(Duration.ofHours(2)), ReceiptStatus.DRAFT, null, null, 0);
        GoodsReceipt saved = receipts.save(draft);
        // Cột confirmed_* chỉ ghi ở đường cập nhật: lưu nháp rồi xác nhận, đúng như luồng nghiệp vụ thật
        saved.confirm(actor, confirmedAt);
        receipts.save(saved);
    }

    private void createItem(UUID tenantId, DemoPlan plan, StockItemPlan s, UUID actor, DemoSeedContext context) {
        if (s.ledgerTotal() != s.onHand()) {
            throw new IllegalStateException("Kế hoạch tồn kho lệch sổ cái ở mặt hàng " + s.index());
        }
        StockItem item = StockItem.restore(DemoIds.of("stock-item", s.index()), tenantId, s.titleId(), s.onHand(),
                s.reserved(), Money.of(s.unitPrice()), s.lowStockThreshold(), true, 0,
                detailsOf(plan, s, context));
        List<StockMovement> movements = movementsOf(tenantId, plan, s, actor, context);
        long onHand = movements.stream().mapToLong(StockMovement::deltaOnHand).sum();
        long reserved = movements.stream().mapToLong(StockMovement::deltaReserved).sum();
        if (onHand != s.onHand() || reserved != s.reserved()) {
            throw new IllegalStateException("Sổ cái dựng ra không khớp tồn kho ở mặt hàng " + s.index());
        }
        stock.insert(item);
        movements.forEach(stock::append);
    }

    /** Chi tiết quản lý theo kế hoạch; lần nhập gần nhất là thời điểm xác nhận phiếu nhập (khớp sổ cái RECEIPT). */
    private static StockItemDetails detailsOf(DemoPlan plan, StockItemPlan s, DemoSeedContext context) {
        Instant restocked = s.receivedQuantity() > 0
                ? receiptTime(plan, plan.receipts().get(s.receiptIndex()), context) : null;
        return StockItemDetails.of(s.sku(), s.supplierName(), s.costPrice(), s.binLocation(), s.reorderQuantity(),
                restocked, s.note());
    }

    /** Điền các trường chi tiết còn null của mặt hàng đã có; true nếu có thay đổi. Không ghi đè giá trị đã có. */
    private boolean topUpDetails(UUID tenantId, DemoPlan plan, StockItemPlan s, DemoSeedContext context) {
        StockItem item = stock.find(tenantId, s.titleId()).orElseThrow();
        StockItemDetails desired = detailsOf(plan, s, context);
        if (desired.sku() != null && item.details().sku() == null
                && stock.findBySku(tenantId, desired.sku()).filter(o -> !o.id().equals(item.id())).isPresent()) {
            desired = desired.withoutSku(); // SKU đã thuộc mặt hàng khác: bỏ qua, không làm hỏng lần chạy
        }
        StockItemDetails merged = item.details().fillMissingFrom(desired);
        if (merged.equals(item.details())) {
            return false;
        }
        stock.saveAttributes(item.withDetails(merged));
        return true;
    }

    private List<StockMovement> movementsOf(UUID tenantId, DemoPlan plan, StockItemPlan s, UUID actor,
                                            DemoSeedContext context) {
        List<StockMovement> list = new ArrayList<>();
        int seq = 0;
        if (s.receivedQuantity() > 0) {
            ReceiptPlan receipt = plan.receipts().get(s.receiptIndex());
            list.add(movement(tenantId, s, seq++, MovementType.RECEIPT, s.receivedQuantity(), 0, "GOODS_RECEIPT",
                    receipt.id(), receiptTime(plan, receipt, context), actor));
        }
        for (OrderPlan o : plan.orders()) {
            if (o.status() == OrderStatus.CANCELLED) {
                continue;
            }
            Instant at = DemoTimes.orderCreatedAt(o, plan, context.clock());
            for (OrderLinePlan line : o.lines()) {
                if (line.titleId().equals(s.titleId())) {
                    list.add(movement(tenantId, s, seq++, MovementType.SALE, -line.quantity(), 0, "ORDER", o.id(), at,
                            actor));
                }
            }
        }
        for (SalesReturnPlan r : plan.salesReturns()) {
            if (r.titleId().equals(s.titleId())) {
                list.add(movement(tenantId, s, seq++, MovementType.RETURN, r.quantity(), 0, "RETURN", r.id(),
                        DemoTimes.returnCreatedAt(r, plan, context.clock()), actor));
            }
        }
        if (s.reserved() > 0) {
            list.add(movement(tenantId, s, seq, MovementType.RESERVE, 0, s.reserved(), "DEMO", null,
                    DemoTimes.clamp(DemoTimes.at(plan.today(), 1, 600), context.clock()), actor));
        }
        return list;
    }

    private static StockMovement movement(UUID tenantId, StockItemPlan s, int seq, MovementType type, long deltaOnHand,
                                          long deltaReserved, String refType, UUID refId, Instant at, UUID actor) {
        return new StockMovement(DemoIds.of("stock-ledger:" + s.index(), seq), tenantId, s.titleId(), type,
                deltaOnHand, deltaReserved, refType, refId, null, actor, at);
    }

    private static Instant receiptTime(DemoPlan plan, ReceiptPlan r, DemoSeedContext context) {
        return DemoTimes.clamp(DemoTimes.at(plan.today(), r.confirmedDaysAgo(), RECEIPT_MINUTE_OF_DAY),
                context.clock());
    }

    private static String phone(int index) {
        return String.format("028%07d", 3_820_000 + index);
    }
}
