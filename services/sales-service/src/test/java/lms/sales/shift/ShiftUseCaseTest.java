package lms.sales.shift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lms.sales.ordering.application.CommitPosOrderUseCase;
import lms.sales.ordering.application.CommitPosPendingOrderUseCase;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.PosOrderResult;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.ordering.infrastructure.client.SimulatedPaymentGateway;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.application.CashierShiftRepository;
import lms.sales.shift.application.CashierShiftUseCases;
import lms.sales.shift.application.ShiftDetailView;
import lms.sales.shift.application.ShiftReport;
import lms.sales.shift.application.ShiftView;
import lms.sales.shift.domain.ShiftEventType;
import lms.sales.shift.domain.ShiftStatus;
import lms.sales.shift.domain.VarianceStatus;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/**
 * Use case ca thu ngân nối với use case thật của ordering trên bộ giả trong bộ nhớ (ADR-0030): gắn tiền vào ca, tiền mặt
 * dự kiến, chênh lệch, ngưỡng, duyệt, mở lại, quyền sở hữu, một ca mở mỗi thu ngân, cờ chặn bán.
 */
class ShiftUseCaseTest {

    private final Harness h = new Harness();
    private final ShiftFixture fx = new ShiftFixture(h);
    private final UUID cashierA = h.user;
    private final UUID cashierB = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway("970422", "0123456789", "LMS DEMO");
    private final CommitPosPendingOrderUseCase commit = new CommitPosPendingOrderUseCase(h.orderRepo, h.intents,
            List.of(gateway), h.stockService, h.getSettings, h.numbers, h.auditPort, h.tenantDirectory, h.returnRepo,
            h.clock, Duration.ofMinutes(10), Duration.ofMinutes(10)).withShiftAttribution(fx.attribution);
    private final PlacePosOrderUseCase place = new PlacePosOrderUseCase(h.orderRepo, h.catalog,
            new CommitPosOrderUseCase(h.orderRepo, h.stockService, h.getSettings, h.numbers, h.auditPort, h.clock,
                    h.tenantDirectory, h.returnRepo), commit);
    private final PaymentIntentUseCases intentUseCases = new PaymentIntentUseCases(h.intents, h.orderRepo,
            h.stockService, h.auditPort, h.clock).withShiftAttribution(fx.attribution);
    private final ProcessPaymentWebhookUseCase webhook = new ProcessPaymentWebhookUseCase(h.intents, h.orderRepo,
            h.stockService, h.auditPort, h.clock);
    private final RefundUseCases refundUseCases = new RefundUseCases(h.refundRepo, h.orderRepo, h.auditPort, h.clock)
            .withShiftAttribution(fx.attribution);
    private final UUID title = h.book("Sách ca", 100_000, null, 100);

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    private ShiftView openShift(UUID cashier, long openingFloat) {
        return h.tx(() -> fx.cashier.open(h.tenant, cashier, fx.open(openingFloat)));
    }

    /** Bán tại quầy: tạo đơn (lệnh PENDING). Số lượng 1 cuốn giá bìa 100.000 nên mỗi đơn 100.000 (đơn nhiều cuốn nhân lên). */
    private PosOrderResult sell(UUID by, PaymentMethod method, int qty) {
        PosOrderResult r = h.tx(() -> place.createWithPayment(new PlacePosOrderCommand(h.tenant, by,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), method, null, null, null, null,
                UUID.randomUUID())));
        fx.trackedOrders.add(r.order().id());
        return r;
    }

    private PaymentIntentResponse confirm(UUID actor, PosOrderResult sale, String cardRef) {
        return h.tx(() -> intentUseCases.confirm(h.tenant, actor, sale.paymentIntent().id(),
                new PaymentIntentUseCases.ConfirmCommand(null, cardRef)));
    }

    private SalesOrder order(PosOrderResult sale) {
        return h.orderRepo.findById(h.tenant, sale.order().id()).orElseThrow();
    }

    private UUID refundCash(UUID actor, PosOrderResult sale, long amount, PaymentMethod method) {
        Refund r = h.refundRepo.save(Refund.forCancellation(h.tenant, sale.order().id(), Money.of(amount),
                h.clock.instant()));
        h.tx(() -> refundUseCases.markRefunded(h.tenant, actor, r.id(), method, null, null));
        return r.id();
    }

    // ---- mở ca ----

    @Test
    void moCaGhiSoCaTienDauCaVaAudit() {
        ShiftView s = openShift(cashierA, 500_000);
        assertEquals(ShiftStatus.OPEN, s.status());
        assertEquals("OPEN", s.phase());
        assertEquals(500_000, s.openingFloat());
        assertEquals(cashierA, s.cashierUserId());
        assertTrue(s.shiftNo().startsWith("S-"), "số ca lấy từ DocumentNumberPort SHIFT: " + s.shiftNo());
        assertTrue(h.audits().stream().anyMatch(a -> a.action().equals(SalesAuditPort.Actions.SHIFT_OPENED)
                && a.aggregateType().equals("CashierShift") && a.aggregateId().equals(s.id())));
        assertEquals(ShiftEventType.OPENED, fx.store.events(h.tenant, s.id()).get(0).type());
    }

    @Test
    void moCaTienDauCaAmBiTuChoi() {
        assertEquals(ErrorCode.INVALID_PRICE, codeOf(() -> openShift(cashierA, -1)));
    }

    @Test
    void moiThuNganToiDaMotCaMo() {
        ShiftView first = openShift(cashierA, 0);
        assertEquals(ErrorCode.SHIFT_ALREADY_OPEN, codeOf(() -> openShift(cashierA, 100)));
        // thu ngân khác vẫn mở được
        assertNotEquals(first.id(), openShift(cashierB, 0).id());
        // đóng rồi mở lại ca mới được
        h.tx(() -> fx.cashier.close(h.tenant, cashierA, new CashierShiftUseCases.CloseCommand(0, null)));
        assertNotEquals(first.id(), openShift(cashierA, 0).id());
    }

    @Test
    void guiLaiCungClientRequestIdTraCaCuKhongMoThem() {
        var cmd = fx.open(100_000);
        ShiftView a = h.tx(() -> fx.cashier.open(h.tenant, cashierA, cmd));
        ShiftView b = h.tx(() -> fx.cashier.open(h.tenant, cashierA, cmd));
        assertEquals(a.id(), b.id());
        assertEquals(1, fx.store.search(h.tenant, new CashierShiftRepository.Criteria(null, null, null, null, null),
                0, 10).total());
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> h.tx(() -> fx.cashier.open(h.tenant, cashierB, cmd))),
                "clientRequestId của người khác");
    }

    @Test
    void haiYeuCauMoCaDongThoiChiMotThanhCong() throws Exception {
        fx.store.openCheckBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    try {
                        return fx.cashier.open(h.tenant, cashierA, fx.open(0));
                    } catch (SalesException e) {
                        return e.code();
                    }
                }));
            }
            long ok = 0;
            long conflicts = 0;
            for (Future<Object> f : results) {
                Object r = f.get();
                if (r instanceof ShiftView) {
                    ok++;
                } else if (r == ErrorCode.SHIFT_ALREADY_OPEN) {
                    conflicts++;
                }
            }
            assertEquals(1, ok, "đúng một yêu cầu mở được ca");
            assertEquals(1, conflicts, "yêu cầu còn lại nhận 409 SHIFT_ALREADY_OPEN");
        } finally {
            fx.store.openCheckBarrier = null;
            pool.shutdownNow();
        }
    }

    // ---- gắn tiền vào ca ----

    @Test
    void coTatKhongCaVanBanDuocTienKhongVaoCaNaoHanhViCu() {
        PosOrderResult sale = sell(cashierA, PaymentMethod.CASH, 1);
        assertNull(h.intents.findByOrderId(h.tenant, sale.order().id()).orElseThrow().shiftId());
        confirm(cashierA, sale, null);
        assertNull(order(sale).payments().get(0).shiftId(), "không có ca thì shiftId null");
        assertEquals(1, order(sale).payments().size());
    }

    @Test
    void thuTayVaoCaCuaNguoiBamXacNhanKhongPhaiNguoiTaoDon() {
        ShiftView a = openShift(cashierA, 0);
        ShiftView b = openShift(cashierB, 0);
        PosOrderResult sale = sell(cashierA, PaymentMethod.CASH, 1); // A tạo đơn
        assertEquals(a.id(), h.intents.findByOrderId(h.tenant, sale.order().id()).orElseThrow().shiftId(),
                "lệnh ghi ca của người tạo đơn");
        confirm(cashierB, sale, null); // B bấm xác nhận thu
        assertEquals(b.id(), order(sale).payments().get(0).shiftId(), "tiền nằm trong ngăn kéo của người bấm");
    }

    @Test
    void thanhToanQuaCongVaoCaCuaNguoiTaoDon() {
        ShiftView a = openShift(cashierA, 0);
        PosOrderResult sale = sell(cashierA, PaymentMethod.QR, 1);
        var intent = sale.paymentIntent();
        h.tx(() -> webhook.execute(h.tenant, new ProcessPaymentWebhookUseCase.Command(intent.reference(),
                intent.amount(), "TXN-1", "PAID")));
        assertEquals(a.id(), order(sale).payments().get(0).shiftId());
        assertEquals(PaymentMethod.QR, order(sale).payments().get(0).method());
    }

    @Test
    void hoanTienVaoCaCuaNguoiGhiNhanHoan() {
        ShiftView a = openShift(cashierA, 0);
        ShiftView b = openShift(cashierB, 0);
        PosOrderResult sale = sell(cashierA, PaymentMethod.CASH, 2);
        confirm(cashierA, sale, null);
        UUID refundId = refundCash(cashierB, sale, 50_000, PaymentMethod.CASH);
        assertEquals(b.id(), h.refundRepo.findById(h.tenant, refundId).orElseThrow().shiftId());
        assertNotEquals(a.id(), h.refundRepo.findById(h.tenant, refundId).orElseThrow().shiftId());
    }

    // ---- cờ chặn bán ----

    @Test
    void coBatThiTaoDonXacNhanVaHoanTienMatKhongCoCaBi409() {
        PosOrderResult early = sell(cashierA, PaymentMethod.CASH, 1); // tạo trước khi bật cờ
        fx.setRequireOpenShift(true);
        assertEquals(ErrorCode.SHIFT_NOT_OPEN, codeOf(() -> sell(cashierA, PaymentMethod.CASH, 1)));
        assertEquals(ErrorCode.SHIFT_NOT_OPEN, codeOf(() -> sell(cashierA, PaymentMethod.QR, 1)));
        assertEquals(ErrorCode.SHIFT_NOT_OPEN, codeOf(() -> confirm(cashierA, early, null)));
        // hoàn tiền mặt bị chặn, hoàn không phải tiền mặt thì không
        Refund r = h.refundRepo.save(Refund.forCancellation(h.tenant, early.order().id(), Money.of(10_000),
                h.clock.instant()));
        assertEquals(ErrorCode.SHIFT_NOT_OPEN, codeOf(() -> h.tx(() -> refundUseCases.markRefunded(h.tenant,
                cashierA, r.id(), PaymentMethod.CASH, null, null))));
        assertNotNull(h.tx(() -> refundUseCases.markRefunded(h.tenant, cashierA, r.id(), PaymentMethod.BANK_TRANSFER,
                null, null)));
        // mở ca thì bán được
        ShiftView s = openShift(cashierA, 0);
        PosOrderResult ok = sell(cashierA, PaymentMethod.CASH, 1);
        confirm(cashierA, ok, null);
        assertEquals(s.id(), order(ok).payments().get(0).shiftId());
        // thu ngân khác chưa có ca vẫn bị chặn
        assertEquals(ErrorCode.SHIFT_NOT_OPEN, codeOf(() -> sell(cashierB, PaymentMethod.CASH, 1)));
    }

    // ---- đóng ca ----

    @Test
    void tienMatDuKienGomThuTienMatTruHoanTienMatConQrTheKhongVaoTienMat() {
        ShiftView s = openShift(cashierA, 500_000);
        PosOrderResult cash1 = sell(cashierA, PaymentMethod.CASH, 2); // 200.000
        PosOrderResult cash2 = sell(cashierA, PaymentMethod.CASH, 1); // 100.000
        PosOrderResult card = sell(cashierA, PaymentMethod.CARD, 3); // 300.000
        PosOrderResult qr = sell(cashierA, PaymentMethod.QR, 4); // 400.000
        confirm(cashierA, cash1, null);
        confirm(cashierA, cash2, null);
        confirm(cashierA, card, "CARD-REF-1");
        h.tx(() -> webhook.execute(h.tenant, new ProcessPaymentWebhookUseCase.Command(qr.paymentIntent().reference(),
                qr.paymentIntent().amount(), "TXN-QR", "PAID")));
        refundCash(cashierA, cash1, 50_000, PaymentMethod.CASH);
        refundCash(cashierA, card, 30_000, PaymentMethod.CARD); // hoàn thẻ không trừ tiền mặt

        ShiftDetailView live = h.tx(() -> fx.cashier.current(h.tenant, cashierA));
        assertEquals(500_000 + 300_000 - 50_000, live.provisionalExpectedCash(), "tạm tính khi ca còn mở");
        ShiftReport report = live.report();
        assertEquals(300_000, report.line("CASH").collected());
        assertEquals(50_000, report.line("CASH").refunded());
        assertEquals(250_000, report.line("CASH").net());
        assertEquals(2, report.line("CASH").paymentCount());
        assertEquals(300_000, report.line("CARD").collected());
        assertEquals(30_000, report.line("CARD").refunded());
        assertEquals(400_000, report.line("QR").collected());
        assertEquals(0, report.line("MOMO").collected(), "phương thức không có giao dịch vẫn có dòng 0");
        assertEquals(4, report.orderCount());
        assertEquals(1_000_000, report.grandTotal());
        assertEquals(1_000_000, report.collectedTotal());
        assertEquals(80_000, report.refundedTotal());
        assertEquals(920_000, report.netTotal());

        ShiftView closed = h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(750_000, null)));
        assertEquals(750_000, closed.expectedCash(), "QR và thẻ không vào tiền mặt dự kiến");
        assertEquals(0, closed.variance());
        assertEquals(VarianceStatus.NONE, closed.varianceStatus());
        assertEquals("CLOSED", closed.phase());
        assertEquals(s.id(), closed.id());
    }

    @Test
    void thieuHoacThuaPhaiCoLyDoVaChoQuanLyDuyet() {
        ShiftView s = openShift(cashierA, 100_000);
        confirm(cashierA, sell(cashierA, PaymentMethod.CASH, 1), null); // dự kiến 200.000
        assertEquals(ErrorCode.SHIFT_VARIANCE_REASON_REQUIRED, codeOf(() -> h.tx(() -> fx.cashier.close(h.tenant,
                cashierA, new CashierShiftUseCases.CloseCommand(190_000, null)))), "thiếu 10.000 không có lý do");
        assertEquals(ShiftStatus.OPEN, fx.store.findById(h.tenant, s.id()).orElseThrow().status());

        ShiftView closed = h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(190_000, "Thối nhầm")));
        assertEquals(-10_000, closed.variance());
        assertEquals("PENDING_APPROVAL", closed.phase());

        ShiftView approved = h.tx(() -> fx.admin.approveVariance(h.tenant, manager, s.id(), "Đã kiểm két"));
        assertEquals(VarianceStatus.APPROVED, approved.varianceStatus());
        assertEquals("CLOSED", approved.phase());
        assertTrue(h.audits().stream().anyMatch(a -> a.action().equals(SalesAuditPort.Actions.SHIFT_VARIANCE_APPROVED)));
        assertEquals(ErrorCode.SHIFT_STATE_CONFLICT,
                codeOf(() -> h.tx(() -> fx.admin.approveVariance(h.tenant, manager, s.id(), null))));
    }

    @Test
    void thuaTienCungPhaiCoLyDo() {
        openShift(cashierA, 0);
        confirm(cashierA, sell(cashierA, PaymentMethod.CASH, 1), null);
        ShiftView closed = h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(105_000, "Khách để lại tiền lẻ")));
        assertEquals(5_000, closed.variance());
        assertEquals(VarianceStatus.PENDING_APPROVAL, closed.varianceStatus());
    }

    @Test
    void nguongTenantChoQuaChenhLechNhoKhongCanLyDo() {
        fx.setThreshold(10_000);
        openShift(cashierA, 0);
        confirm(cashierA, sell(cashierA, PaymentMethod.CASH, 1), null);
        ShiftView closed = h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(95_000, null)));
        assertEquals(-5_000, closed.variance());
        assertEquals(VarianceStatus.NONE, closed.varianceStatus());
    }

    @Test
    void khongDongCaKhiConLenhThuTayChoXacNhanLenhHetHanThiKhongChan() {
        ShiftView s = openShift(cashierA, 0);
        PosOrderResult pending = sell(cashierA, PaymentMethod.CASH, 1);
        SalesException e = assertThrows(SalesException.class, () -> h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(0, null))));
        assertEquals(ErrorCode.SHIFT_HAS_PENDING_PAYMENTS, e.code());
        assertEquals(1L, e.details().get("pendingPayments"));
        assertEquals(ShiftStatus.OPEN, fx.store.findById(h.tenant, s.id()).orElseThrow().status());

        confirm(cashierA, pending, null);
        PosOrderResult pending2 = sell(cashierA, PaymentMethod.CASH, 1);
        assertNotNull(pending2);
        h.clock.advance(Duration.ofMinutes(11)); // lệnh tự hết hạn sau 10 phút (ADR-0021) nên không còn chặn
        ShiftView closed = h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(100_000, null)));
        assertEquals(100_000, closed.expectedCash());
    }

    @Test
    void lenhQrChoThanhToanKhongChanDongCaVaTienDenMuonKhongDoiSoDaChup() {
        ShiftView s = openShift(cashierA, 0);
        PosOrderResult qr = sell(cashierA, PaymentMethod.QR, 1);
        ShiftView closed = h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(0, null)));
        assertEquals(0, closed.expectedCash());
        h.clock.advance(Duration.ofMinutes(5));
        h.tx(() -> webhook.execute(h.tenant, new ProcessPaymentWebhookUseCase.Command(qr.paymentIntent().reference(),
                qr.paymentIntent().amount(), "TXN-LATE", "PAID")));
        ShiftDetailView detail = h.tx(() -> fx.admin.get(h.tenant, s.id()));
        assertEquals(0, detail.shift().expectedCash(), "tiền mặt dự kiến đã chụp lúc đóng, không đổi");
        assertEquals(100_000, detail.report().line("QR").collected());
        assertEquals(100_000, detail.report().line("QR").collectedAfterClose(), "tách phần phát sinh sau khi chốt");
        assertEquals(1, detail.report().line("QR").paymentCountAfterClose());
    }

    @Test
    void auditMoiThaoTacCaKhongChuaLyDoTuDo() {
        ShiftView s = openShift(cashierA, 0);
        h.tx(() -> fx.cashier.close(h.tenant, cashierA, new CashierShiftUseCases.CloseCommand(7, "Ly do tu do")));
        h.tx(() -> fx.admin.approveVariance(h.tenant, manager, s.id(), "ghi chu duyet"));
        h.tx(() -> fx.admin.reopen(h.tenant, manager, s.id(), "mo lai vi dem nham"));
        var actions = h.audits().stream().map(SalesAuditPort.AuditEntry::action).toList();
        assertTrue(actions.containsAll(List.of(SalesAuditPort.Actions.SHIFT_OPENED, SalesAuditPort.Actions.SHIFT_CLOSED,
                SalesAuditPort.Actions.SHIFT_VARIANCE_APPROVED, SalesAuditPort.Actions.SHIFT_REOPENED)), actions.toString());
        h.audits().stream().filter(a -> a.aggregateType().equals("CashierShift")).forEach(a ->
                assertTrue(a.data().values().stream().noneMatch(v -> String.valueOf(v).contains("Ly do tu do")
                        || String.valueOf(v).contains("ghi chu duyet") || String.valueOf(v).contains("mo lai")),
                        "audit/Kafka chỉ mang id, số tiền, mã trạng thái: " + a.data()));
    }

    // ---- quyền sở hữu ----

    @Test
    void thuNganChiThaoTacCaCuaMinhCaNguoiKhacLa404CungThan() {
        ShiftView a = openShift(cashierA, 0);
        assertEquals(ErrorCode.SHIFT_NOT_FOUND, codeOf(() -> h.tx(() -> fx.cashier.getMine(h.tenant, cashierB, a.id()))));
        SalesException other = assertThrows(SalesException.class, () -> fx.cashier.getMine(h.tenant, cashierB, a.id()));
        SalesException missing = assertThrows(SalesException.class,
                () -> fx.cashier.getMine(h.tenant, cashierB, UUID.randomUUID()));
        assertEquals(missing.getMessage(), other.getMessage(), "cùng thân với ca không tồn tại, không lộ sự tồn tại");
        assertEquals(missing.code(), other.code());
        assertEquals(ErrorCode.SHIFT_NOT_FOUND, codeOf(() -> fx.cashier.current(h.tenant, cashierB)));
        assertEquals(ErrorCode.SHIFT_NOT_FOUND, codeOf(() -> h.tx(() -> fx.cashier.close(h.tenant, cashierB,
                new CashierShiftUseCases.CloseCommand(0, null)))), "B không đóng được ca của A qua endpoint của tôi");
        assertTrue(fx.cashier.mine(h.tenant, cashierB, null, 0, 10).items().isEmpty());
        assertEquals(1, fx.cashier.mine(h.tenant, cashierA, null, 0, 10).total());
        assertEquals(a.id(), fx.cashier.getMine(h.tenant, cashierA, a.id()).shift().id());
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> fx.cashier.current(h.tenant, null)));
    }

    @Test
    void quanLyXemTimVaDongThayMoiCa() {
        ShiftView a = openShift(cashierA, 0);
        openShift(cashierB, 0);
        assertEquals(2, fx.admin.search(h.tenant, new CashierShiftRepository.Criteria(null, null, null, null, null),
                0, 10).total());
        assertEquals(1, fx.admin.search(h.tenant, new CashierShiftRepository.Criteria(cashierA, null, null, null,
                null), 0, 10).total());
        assertEquals(a.id(), fx.admin.get(h.tenant, a.id()).shift().id());
        ShiftView closed = h.tx(() -> fx.admin.closeOnBehalf(h.tenant, manager, a.id(),
                new CashierShiftUseCases.CloseCommand(0, null)));
        assertEquals(manager, closed.closedBy());
        assertEquals(ErrorCode.SHIFT_NOT_FOUND, codeOf(() -> fx.admin.get(h.tenant, UUID.randomUUID())));
    }

    // ---- mở lại ----

    @Test
    void moLaiCanLyDoXoaSoLieuGhiLichSuVaDongLaiRaSoLieuMoi() {
        ShiftView s = openShift(cashierA, 100_000);
        confirm(cashierA, sell(cashierA, PaymentMethod.CASH, 1), null);
        h.tx(() -> fx.cashier.close(h.tenant, cashierA, new CashierShiftUseCases.CloseCommand(150_000, "thiếu 50k")));
        assertEquals(ErrorCode.REOPEN_REASON_REQUIRED, codeOf(() -> h.tx(() -> fx.admin.reopen(h.tenant, manager,
                s.id(), "  "))));
        ShiftView reopened = h.tx(() -> fx.admin.reopen(h.tenant, manager, s.id(), "Đếm nhầm"));
        assertEquals("REOPENED", reopened.phase());
        assertEquals(1, reopened.reopenCount());
        assertNull(reopened.expectedCash());
        assertNull(reopened.variance());
        ShiftView again = h.tx(() -> fx.cashier.close(h.tenant, cashierA,
                new CashierShiftUseCases.CloseCommand(200_000, null)));
        assertEquals(200_000, again.expectedCash());
        assertEquals(VarianceStatus.NONE, again.varianceStatus());
        var types = fx.store.events(h.tenant, s.id()).stream().map(e -> e.type()).toList();
        assertEquals(List.of(ShiftEventType.OPENED, ShiftEventType.CLOSED, ShiftEventType.REOPENED,
                ShiftEventType.CLOSED), types);
        assertEquals("Đếm nhầm", fx.store.events(h.tenant, s.id()).get(2).snapshot().get("reason"));
    }

    @Test
    void khongMoLaiKhiThuNganDangCoCaMoKhac() {
        ShiftView old = openShift(cashierA, 0);
        h.tx(() -> fx.cashier.close(h.tenant, cashierA, new CashierShiftUseCases.CloseCommand(0, null)));
        openShift(cashierA, 0); // ca mới
        assertEquals(ErrorCode.SHIFT_ALREADY_OPEN,
                codeOf(() -> h.tx(() -> fx.admin.reopen(h.tenant, manager, old.id(), "lý do"))));
        assertEquals(ErrorCode.SHIFT_STATE_CONFLICT,
                codeOf(() -> h.tx(() -> fx.admin.reopen(h.tenant, manager, fx.cashier.current(h.tenant, cashierA)
                        .shift().id(), "ca đang mở"))));
    }

    // ---- cấu hình ----

    @Test
    void capNhatCauHinhKiemPhienBanVaAudit() {
        var d = fx.settings.get(h.tenant);
        assertEquals(0, d.version());
        assertEquals(false, d.requireOpenShiftForPos());
        var saved = h.tx(() -> fx.settings.update(h.tenant, manager, true, 20_000, 0));
        assertEquals(1, saved.version());
        assertTrue(saved.requireOpenShiftForPos());
        assertEquals(ErrorCode.SETTINGS_VERSION_CONFLICT,
                codeOf(() -> h.tx(() -> fx.settings.update(h.tenant, manager, false, 0, 0))));
        assertTrue(h.audits().stream().anyMatch(a -> a.action().equals(SalesAuditPort.Actions.SHIFT_SETTINGS_CHANGED)));
    }
}
