package lms.sales.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.demo.plan.DemoPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.demo.runner.DemoSeedStep.SeedResult;
import lms.sales.ordering.application.RefundRepository;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.ordering.domain.SalesReturn;
import lms.sales.ordering.infrastructure.demo.OrderingDemoSeedStep;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.settings.infrastructure.demo.SettingsDemoSeedStep;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.shared.infrastructure.demo.DemoTimes;
import lms.sales.stock.application.GoodsReceiptRepository;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.infrastructure.demo.StockDemoSeedStep;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.ReceiptStatus;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockMovement;
import lms.sales.stock.domain.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** Đơn vị cho các bước seed bán hàng (ADR-0018), cổng lưu trữ giả trong bộ nhớ. */
class SalesDemoSeedStepsTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final java.time.ZoneId VN = DemoTimes.BUSINESS_ZONE;

    private final Map<UUID, SalesOrder> orders = new HashMap<>();
    private final Map<UUID, SalesReturn> returns = new HashMap<>();
    private final Map<UUID, Refund> refunds = new HashMap<>();
    private final Map<UUID, StockItem> items = new HashMap<>();
    private final List<StockMovement> ledger = new ArrayList<>();
    private final Map<UUID, Supplier> suppliers = new HashMap<>();
    private final Map<UUID, GoodsReceipt> receipts = new HashMap<>();
    private final Map<UUID, SalesSettings> settings = new HashMap<>();
    private final Set<UUID> touched = new HashSet<>();

    private final List<DemoSeedStep> steps = buildSteps();

    @SuppressWarnings("unchecked")
    private List<DemoSeedStep> buildSteps() {
        SalesOrderRepository orderRepo = mock(SalesOrderRepository.class);
        when(orderRepo.findById(any(), any())).thenAnswer(a -> Optional.ofNullable(orders.get(a.<UUID>getArgument(1))));
        when(orderRepo.save(any())).thenAnswer(a -> {
            SalesOrder o = a.getArgument(0);
            orders.put(o.id(), o);
            return o;
        });
        SalesReturnRepository returnRepo = mock(SalesReturnRepository.class);
        when(returnRepo.findById(any(), any())).thenAnswer(a -> Optional.ofNullable(returns.get(a.<UUID>getArgument(1))));
        when(returnRepo.save(any())).thenAnswer(a -> {
            SalesReturn r = a.getArgument(0);
            returns.put(r.id(), r);
            return r;
        });
        RefundRepository refundRepo = mock(RefundRepository.class);
        when(refundRepo.findById(any(), any())).thenAnswer(a -> Optional.ofNullable(refunds.get(a.<UUID>getArgument(1))));
        when(refundRepo.save(any())).thenAnswer(a -> {
            Refund r = a.getArgument(0);
            refunds.put(r.id(), r);
            return r;
        });
        TenantDirectoryPort directory = mock(TenantDirectoryPort.class);
        org.mockito.Mockito.doAnswer(a -> {
            touched.add(a.<UUID>getArgument(0));
            return null;
        }).when(directory).touch(any());

        StockPort stock = mock(StockPort.class);
        when(stock.find(any(), any())).thenAnswer(a -> Optional.ofNullable(items.get(a.<UUID>getArgument(1))));
        when(stock.insert(any())).thenAnswer(a -> {
            StockItem i = a.getArgument(0);
            items.put(i.titleId(), i);
            return i;
        });
        when(stock.saveAttributes(any())).thenAnswer(a -> {
            StockItem i = a.getArgument(0);
            items.put(i.titleId(), i);
            return i;
        });
        org.mockito.Mockito.doAnswer(a -> {
            ledger.add(a.<StockMovement>getArgument(0));
            return null;
        }).when(stock).append(any());
        SupplierRepository supplierRepo = mock(SupplierRepository.class);
        when(supplierRepo.findById(any(), any())).thenAnswer(a -> Optional.ofNullable(suppliers.get(a.<UUID>getArgument(1))));
        when(supplierRepo.existsByName(any(), anyString(), any())).thenAnswer(a -> suppliers.values().stream()
                .anyMatch(s -> s.name().equalsIgnoreCase(a.getArgument(1))));
        when(supplierRepo.save(any())).thenAnswer(a -> {
            Supplier s = a.getArgument(0);
            suppliers.put(s.id(), s);
            return s;
        });
        GoodsReceiptRepository receiptRepo = mock(GoodsReceiptRepository.class);
        when(receiptRepo.findById(any(), any())).thenAnswer(a -> Optional.ofNullable(receipts.get(a.<UUID>getArgument(1))));
        when(receiptRepo.save(any())).thenAnswer(a -> {
            GoodsReceipt r = a.getArgument(0);
            receipts.put(r.id(), r);
            return r;
        });
        SalesSettingsPort settingsPort = mock(SalesSettingsPort.class);
        when(settingsPort.find(any())).thenAnswer(a -> Optional.ofNullable(settings.get(a.<UUID>getArgument(0))));
        when(settingsPort.save(any())).thenAnswer(a -> {
            SalesSettings s = a.getArgument(0);
            settings.put(s.tenantId(), s);
            return s;
        });
        TransactionOperations tx = TransactionOperations.withoutTransaction();
        return List.of(new SettingsDemoSeedStep(settingsPort, tx),
                new StockDemoSeedStep(supplierRepo, receiptRepo, stock, tx),
                new OrderingDemoSeedStep(orderRepo, returnRepo, refundRepo, directory, tx));
    }

    private static Clock clockAt(String isoVn) {
        return Clock.fixed(java.time.LocalDateTime.parse(isoVn).atZone(VN).toInstant(), VN);
    }

    private SeedResult[] seed(Clock clock) {
        DemoPlan plan = DemoPlan.generate(LocalDate.now(clock), DemoPlan.DEFAULT_SEED);
        DemoSeedContext ctx = new DemoSeedContext(TENANT, plan, "demo", clock);
        return steps.stream().sorted(java.util.Comparator.comparingInt(DemoSeedStep::order))
                .map(s -> s.run(ctx)).toArray(SeedResult[]::new);
    }

    @Test
    void taoDungSoLuongTheoKeHoach() {
        SeedResult[] results = seed(clockAt("2026-11-17T15:00:00"));
        assertThat(settings).hasSize(1);
        assertThat(settings.get(TENANT).lowStockThreshold()).isEqualTo(DemoPlan.GENERAL_LOW_STOCK_THRESHOLD);
        assertThat(suppliers).hasSize(2);
        assertThat(receipts).hasSize(2).allSatisfy((id, r) -> {
            assertThat(r.status()).isEqualTo(ReceiptStatus.CONFIRMED);
            assertThat(r.confirmedAt()).isNotNull();
        });
        assertThat(items).hasSize(32);
        assertThat(orders).hasSize(42);
        assertThat(returns).hasSize(3);
        assertThat(refunds).hasSize(4);
        assertThat(touched).containsExactly(TENANT);
        // 1 cấu hình; 2 NCC + 2 phiếu nhập + 32 mặt hàng; 42 đơn + 3 phiếu trả + 4 khoản hoàn
        assertThat(results[0].created()).isEqualTo(1);
        assertThat(results[1].created()).isEqualTo(2 + 2 + 32);
        assertThat(results[2].created()).isEqualTo(42 + 3 + 4);
        assertThat(orders.values()).allSatisfy(o -> assertThat(o.orderNo()).startsWith("DEMO-"));
    }

    @Test
    void chayLaiKhongNhanDoiKhongLoiKeCaKhiSangNgayKhac() {
        seed(clockAt("2026-11-17T15:00:00"));
        int orderCount = orders.size();
        int ledgerCount = ledger.size();
        SeedResult[] again = seed(clockAt("2026-11-17T15:30:00"));
        SeedResult[] nextDay = seed(clockAt("2026-11-20T09:00:00"));
        for (SeedResult[] run : List.of(again, nextDay)) {
            assertThat(run).allSatisfy(r -> assertThat(r.created()).isZero());
            assertThat(run[0].skipped()).isEqualTo(1);
            assertThat(run[1].skipped()).isEqualTo(2 + 2 + 32);
            assertThat(run[2].skipped()).isEqualTo(42 + 3 + 4);
        }
        assertThat(orders).hasSize(orderCount);
        assertThat(ledger).hasSize(ledgerCount);
        assertThat(items).hasSize(32);
    }

    @Test
    void dungNamMatHangSapHetKeCaHaiChieuNguongRieng() {
        seed(clockAt("2026-11-17T15:00:00"));
        int threshold = settings.get(TENANT).lowStockThreshold();
        List<StockItem> low = items.values().stream().filter(i -> i.isLowStock(threshold)).toList();
        assertThat(low).hasSize(DemoPlan.LOW_STOCK_ITEMS);
        DemoPlan plan = DemoPlan.generate(LocalDate.of(2026, 11, 17), DemoPlan.DEFAULT_SEED);
        assertThat(low.stream().map(StockItem::titleId).toList())
                .containsExactlyInAnyOrderElementsOf(plan.lowStockItems().stream().map(DemoPlan.StockItemPlan::titleId).toList());
        // ngưỡng riêng thắng ngưỡng chung ở cả hai chiều
        StockItem moreThanGeneral = items.get(plan.stockItems().get(3).titleId());
        StockItem lessThanGeneral = items.get(plan.stockItems().get(5).titleId());
        assertThat(moreThanGeneral.available()).isGreaterThan(threshold);
        assertThat(moreThanGeneral.isLowStock(threshold)).isTrue();
        assertThat(lessThanGeneral.available()).isLessThanOrEqualTo(threshold);
        assertThat(lessThanGeneral.isLowStock(threshold)).isFalse();
        assertThat(items.values()).anyMatch(i -> i.available() == 0);
    }

    @Test
    void soCaiCongDungOnHandVaReserved() {
        seed(clockAt("2026-11-17T15:00:00"));
        for (StockItem item : items.values()) {
            List<StockMovement> own = ledger.stream().filter(m -> m.titleId().equals(item.titleId())).toList();
            assertThat(own.stream().mapToLong(StockMovement::deltaOnHand).sum()).as("onHand %s", item.titleId())
                    .isEqualTo(item.onHand());
            assertThat(own.stream().mapToLong(StockMovement::deltaReserved).sum()).as("reserved %s", item.titleId())
                    .isEqualTo(item.reserved());
            assertThat(item.onHand()).isGreaterThanOrEqualTo(item.reserved());
        }
        assertThat(ledger.stream().map(StockMovement::id).distinct()).hasSameSizeAs(ledger);
    }

    @Test
    void tongTienVaMocThanhToanKhopKeHoach() {
        Clock clock = clockAt("2026-11-17T15:00:00");
        seed(clock);
        DemoPlan plan = DemoPlan.generate(LocalDate.now(clock), DemoPlan.DEFAULT_SEED);
        for (DemoPlan.OrderPlan p : plan.orders()) {
            SalesOrder o = orders.get(p.id());
            assertThat(o.orderNo()).isEqualTo(p.orderNo());
            assertThat(o.grandTotal().amount()).as(p.orderNo()).isEqualTo(p.total());
            assertThat(o.subtotal().amount()).isEqualTo(p.subtotal());
            assertThat(o.discountTotal().amount()).isEqualTo(p.discount());
            assertThat(o.vatTotal().amount()).isEqualTo(p.vat());
            assertThat(o.status().name()).isEqualTo(p.status().name());
            assertThat(o.paymentStatus().name()).isEqualTo(p.paymentStatus().name());
            assertThat(o.lines()).hasSameSizeAs(p.lines());
            // paid_at chỉ có ở đơn đã thanh toán, kể cả đơn thanh toán rồi hủy; bản ghi thu đủ grandTotal
            if (p.isPaid()) {
                assertThat(o.paidAt()).as(p.orderNo()).isNotNull();
                assertThat(o.payments()).hasSize(1);
                assertThat(o.payments().get(0).amount()).isEqualTo(o.grandTotal());
            } else {
                assertThat(o.paidAt()).as(p.orderNo()).isNull();
                assertThat(o.payments()).isEmpty();
            }
            // dòng đơn: (đơn, đầu sách) không trùng, line_no liên tục từ 1
            assertThat(o.lines().stream().map(l -> l.titleId()).distinct()).hasSameSizeAs(o.lines());
            for (int i = 0; i < o.lines().size(); i++) {
                assertThat(o.lines().get(i).lineNo()).isEqualTo(i + 1);
            }
        }
        // đơn thanh toán rồi hủy (DEMO-0006): giữ paid_at, có mốc hủy sau mốc thanh toán, khoản hoàn REFUNDED
        SalesOrder paidThenCancelled = orders.get(plan.orders().get(5).id());
        assertThat(paidThenCancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paidThenCancelled.paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paidThenCancelled.paidAt()).isNotNull().isBefore(paidThenCancelled.cancelledAt());
        // đơn chưa thanh toán bị hủy (DEMO-0007): không có paid_at
        assertThat(orders.get(plan.orders().get(6).id()).paidAt()).isNull();
        assertThat(orders.values().stream().filter(o -> o.channel().name().equals("POS")).count()).isPositive();
        assertThat(orders.values().stream().filter(o -> o.channel().name().equals("ONLINE")).count()).isPositive();
    }

    @Test
    void hoanTienVaPhieuTra() {
        seed(clockAt("2026-11-17T15:00:00"));
        assertThat(refunds.values().stream().filter(r -> r.status() == RefundStatus.REFUNDED)).hasSize(3)
                .allSatisfy(r -> {
                    assertThat(r.method()).isNotNull();
                    assertThat(r.refundedAt()).isNotNull().isAfterOrEqualTo(r.createdAt());
                });
        assertThat(refunds.values().stream().filter(r -> r.status() == RefundStatus.PENDING)).hasSize(1)
                .allSatisfy(r -> assertThat(r.refundedAt()).isNull());
        assertThat(refunds.values().stream().filter(r -> r.source().name().equals("CANCELLATION"))).hasSize(1);
        for (SalesReturn r : returns.values()) {
            SalesOrder order = orders.get(r.orderId());
            assertThat(r.createdAt()).isAfterOrEqualTo(order.createdAt());
            assertThat(r.lines()).hasSize(1);
            assertThat(order.lines().stream().map(l -> l.id())).contains(r.lines().get(0).orderLineId());
        }
    }

    @Test
    void khongMocThoiGianNaoVuotHienTaiKhiChayBuoiSangSom() {
        Clock clock = clockAt("2026-11-17T00:20:00");
        seed(clock);
        Instant now = clock.instant();
        assertThat(orders.values()).anySatisfy(o -> assertThat(o.createdAt()).isEqualTo(now)); // đơn hôm nay bị kẹp
        for (SalesOrder o : orders.values()) {
            for (Instant t : new Instant[]{o.createdAt(), o.confirmedAt(), o.paidAt(), o.completedAt(), o.cancelledAt()}) {
                if (t != null) {
                    assertThat(t).isBeforeOrEqualTo(now);
                }
            }
            assertThat(o.createdAt()).isBeforeOrEqualTo(o.paidAt() == null ? now : o.paidAt());
        }
        assertThat(returns.values()).allSatisfy(r -> assertThat(r.createdAt()).isBeforeOrEqualTo(now));
        assertThat(refunds.values()).allSatisfy(r -> {
            assertThat(r.createdAt()).isBeforeOrEqualTo(now);
            if (r.refundedAt() != null) {
                assertThat(r.refundedAt()).isBeforeOrEqualTo(now);
            }
        });
        assertThat(ledger).allSatisfy(m -> assertThat(m.occurredAt()).isBeforeOrEqualTo(now));
        assertThat(receipts.values()).allSatisfy(r -> assertThat(r.confirmedAt()).isBeforeOrEqualTo(now));
    }

    @Test
    void matHangCoChiTietQuanLyDayDuVaKhopSoCai() {
        seed(clockAt("2026-11-17T15:00:00"));
        assertThat(items.values()).allSatisfy(i -> {
            var d = i.details();
            assertThat(d.sku()).matches("LMS-\\d{4}-\\d{4}");
            assertThat(d.supplier()).isNotBlank();
            assertThat(d.binLocation()).matches("K\\d-[A-D]\\d");
            assertThat(d.reorderQuantity()).isPositive();
            double ratio = (double) d.costPrice().amount() / i.sellingPrice().amount();
            assertThat(ratio).isBetween(0.54, 0.71);
            assertThat(d.lastRestockedAt()).isNotNull();
        });
        assertThat(items.values().stream().map(i -> i.details().sku()).distinct()).hasSize(items.size());
        assertThat(items.values().stream().map(i -> i.details().supplier()).distinct().toList())
                .contains("Fahasa", "Nhà sách Phương Nam", "Nhã Nam", "First News - Trí Việt");
        // lần nhập gần nhất khớp thời điểm sổ cái RECEIPT của chính mặt hàng
        items.values().forEach(i -> assertThat(ledger.stream().filter(m -> m.titleId().equals(i.titleId())
                && m.type() == lms.sales.stock.domain.MovementType.RECEIPT).map(StockMovement::occurredAt))
                .contains(i.details().lastRestockedAt()));
    }

    @Test
    void donCoGhiChuKhachNoiBoVaNgayGiaoDuKienHopLe() {
        seed(clockAt("2026-11-17T15:00:00"));
        assertThat(orders.values().stream().filter(o -> o.customerNote() != null)).hasSizeGreaterThan(10);
        assertThat(orders.values().stream().filter(o -> o.staffNote() != null)).hasSizeGreaterThan(10);
        assertThat(orders.values()).allSatisfy(o -> {
            if (o.expectedDeliveryDate() != null) {
                assertThat(o.fulfilment()).isNotEqualTo(lms.sales.ordering.domain.Fulfilment.COUNTER);
                assertThat(o.expectedDeliveryDate()).isAfterOrEqualTo(o.createdAt().atZone(VN).toLocalDate());
            }
        });
        assertThat(orders.values().stream().filter(o -> o.status() == lms.sales.ordering.domain.OrderStatus.CANCELLED))
                .allSatisfy(o -> assertThat(o.expectedDeliveryDate()).isNull());
    }

    @Test
    void chayLaiBoSungChiTietCuaBanGhiCuChiKhiConTrong() {
        seed(clockAt("2026-11-17T15:00:00"));
        // mô phỏng dữ liệu seed cũ: mặt hàng không có chi tiết (một cái có SKU riêng), đơn không có ghi chú
        var all = List.copyOf(items.values());
        StockItem keep = all.get(0);
        StockItem strip = all.get(1);
        items.put(keep.titleId(), keep.withDetails(lms.sales.stock.domain.StockItemDetails.of("MY-SKU", null, null,
                null, null, null, null)));
        items.put(strip.titleId(), strip.withDetails(lms.sales.stock.domain.StockItemDetails.EMPTY));
        var order = orders.values().iterator().next();
        orders.put(order.id(), SalesOrder.restore(new SalesOrder.Snapshot(order.id(), order.tenantId(),
                order.orderNo(), order.channel(), order.status(), order.paymentStatus(), order.paymentMethod(),
                order.customerUserId(), null, null, order.fulfilment(), order.recipient(), order.vatRateBps(),
                order.pricesIncludeVat(), order.subtotal(), order.discountTotal(), order.vatTotal(),
                order.shippingFee(), order.grandTotal(), order.orderDiscount(), order.clientRequestId(),
                order.exchangeOfReturnId(), order.createdBy(), order.createdAt(), order.confirmedAt(),
                order.confirmedBy(), order.paidAt(), order.completedAt(), order.cancelledAt(), order.cancelledBy(),
                order.cancelReason(), order.piiAnonymizedAt(), order.lines(), order.payments(), order.version(),
                "Ghi chú có sẵn", null, null)));

        SeedResult[] again = seed(clockAt("2026-11-17T15:30:00"));
        assertThat(again[1].created()).isEqualTo(2); // chỉ hai mặt hàng được bổ sung
        assertThat(items.get(keep.titleId()).details().sku()).isEqualTo("MY-SKU"); // không ghi đè
        assertThat(items.get(keep.titleId()).details().supplier()).isNotNull();    // trường trống được điền
        assertThat(items.get(strip.titleId()).details().sku()).startsWith("LMS-");
        assertThat(orders.get(order.id()).customerNote()).isEqualTo("Ghi chú có sẵn");
        // chạy lần nữa: không còn gì để làm
        SeedResult[] third = seed(clockAt("2026-11-17T15:45:00"));
        assertThat(third).allSatisfy(r -> assertThat(r.created()).isZero());
    }

    @Test
    void thieuTenantThiTuChoi() {
        DemoSeedContext noTenant = new DemoSeedContext(null, DemoPlan.generate(LocalDate.of(2026, 11, 17)), "demo",
                clockAt("2026-11-17T15:00:00"));
        for (DemoSeedStep s : steps) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.run(noTenant))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
