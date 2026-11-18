package lms.sales.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lms.sales.invoicing.application.BuyerInput;
import lms.sales.invoicing.application.InvoiceQueryUseCases;
import lms.sales.invoicing.application.InvoiceUseCases;
import lms.sales.invoicing.application.InvoiceView;
import lms.sales.invoicing.domain.InvoiceKind;
import lms.sales.invoicing.domain.InvoiceStatus;
import lms.sales.invoicing.support.InvoicingKit;
import lms.sales.ordering.application.CreateReturnCommand;
import lms.sales.ordering.application.OrderResponse;
import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Use case hóa đơn trên nền Harness (đơn/trả hàng/tồn giả) và kho hóa đơn giả: điều kiện lập, số tuần tự, hủy, thay thế,
 * điều chỉnh khi trả hàng, tự phát hành, yêu cầu của khách (IDOR), phát hành song song. Khóa dòng, RLS, trigger bất biến
 * và chỉ mục duy nhất thật được kiểm ở InvoicingIT (Postgres).
 */
class InvoiceUseCasesTest {

    private final Harness h = new Harness();
    private InvoicingKit kit;
    private final UUID customer = UUID.randomUUID();
    private final UUID otherCustomer = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        kit = new InvoicingKit(h);
    }

    // ---- dựng đơn ----

    private OrderResponse posOrder(UUID title, int qty) {
        return h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, qty, null)), PaymentMethod.CASH, null, null, null, null,
                UUID.randomUUID())));
    }

    private OrderResponse onlineCompleted(UUID title, int qty, Fulfilment f, Long shippingFee) {
        var placed = h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(title, qty)), f, PaymentMethod.BANK_TRANSFER,
                new Recipient("Nguyễn Văn An", "0912345678", "12 Lê Lợi, Quận 1"), UUID.randomUUID())));
        UUID id = placed.order().id();
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, id, shippingFee, null));
        h.tx(() -> h.workflow.markPaid(h.tenant, h.user, id, PaymentMethod.BANK_TRANSFER, "CK1", null));
        if (f == Fulfilment.DELIVERY) {
            h.tx(() -> h.workflow.markShipping(h.tenant, h.user, id, null));
        } else {
            h.tx(() -> h.workflow.markReadyForPickup(h.tenant, h.user, id, null));
        }
        return h.tx(() -> h.workflow.complete(h.tenant, h.user, id, null));
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    private InvoiceView issue(UUID orderId) {
        return kit.useCases.issue(new InvoiceUseCases.IssueCommand(h.tenant, orderId, h.user, UUID.randomUUID()));
    }

    // ---- phát hành ----

    @Test
    void chiLapChoDonHoanTatVaDaThuTien() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        // đơn online mới đặt: PENDING_CONFIRMATION + UNPAID
        var placed = h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER,
                new Recipient("An", "0912345678", null), UUID.randomUUID())));
        assertThat(codeOf(() -> issue(placed.order().id()))).isEqualTo(ErrorCode.ORDER_NOT_INVOICEABLE);
        // đã thu nhưng chưa hoàn tất vẫn chưa lập được
        h.tx(() -> h.workflow.confirm(h.tenant, h.user, placed.order().id(), null, null));
        h.tx(() -> h.workflow.markPaid(h.tenant, h.user, placed.order().id(), PaymentMethod.BANK_TRANSFER, "x", null));
        assertThat(codeOf(() -> issue(placed.order().id()))).isEqualTo(ErrorCode.ORDER_NOT_INVOICEABLE);
        // đơn không tồn tại
        assertThat(codeOf(() -> issue(UUID.randomUUID()))).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(kit.invoices.all()).isEmpty();
    }

    @Test
    void chuaCauHinhNguoiBanThi409VaKhongTieuSo() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        assertThat(codeOf(() -> issue(o.id()))).isEqualTo(ErrorCode.INVOICE_SETTINGS_INCOMPLETE);
        assertThat(kit.counterNames).isEmpty();
    }

    @Test
    void phatHanhDonPosSaoDongVaThueTheoDongCapSoTuan_tu() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        UUID b = h.book("Sách B", 33_335, null, 10);
        OrderResponse o = h.tx(() -> h.placePos.create(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(a, 2, null), new PlacePosOrderCommand.Line(b, 3, null)),
                PaymentMethod.CASH, null, null, null, null, UUID.randomUUID())));

        InvoiceView inv = issue(o.id());
        assertThat(inv.status()).isEqualTo("ISSUED");
        assertThat(inv.kind()).isEqualTo("ORIGINAL");
        assertThat(inv.invoiceNo()).isEqualTo(1);
        assertThat(inv.invoiceNumber()).isEqualTo("00000001");
        assertThat(inv.series()).isEqualTo("C26TAA");
        assertThat(inv.templateCode()).isEqualTo("1");
        assertThat(inv.grandTotal()).isEqualTo(o.grandTotal());
        assertThat(inv.vatTotal()).as("tổng thuế các dòng = thuế của đơn").isEqualTo(o.vatTotal());
        assertThat(inv.totalBeforeVat() + inv.vatTotal()).isEqualTo(inv.grandTotal());
        assertThat(inv.lines()).hasSize(2);
        assertThat(inv.lines()).allSatisfy(l -> assertThat(l.vatRateBps()).isEqualTo(500));
        assertThat(inv.lines().get(0).unit()).isEqualTo("cuốn");
        assertThat(inv.buyer().name()).isEqualTo("Người mua không lấy hóa đơn");
        assertThat(inv.buyer().taxCode()).isNull();
        assertThat(inv.seller().taxCode()).isEqualTo("0123456789");
        assertThat(inv.amountInWords()).endsWith("đồng").startsWith("Ba trăm");
        assertThat(inv.notice()).contains("không thay thế hóa đơn điện tử");
        assertThat(kit.counterNames).containsExactly("INVOICE:1:C26TAA:" + kit.year());

        var audits = kit.auditsOf(SalesAuditPort.Actions.INVOICE_ISSUED);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).aggregateType()).isEqualTo("Invoice");
        assertThat(audits.get(0).data()).containsKeys("invoiceId", "invoiceNo", "series", "kind", "orderId", "grandTotal")
                .doesNotContainKeys("buyerName", "buyer", "address");
    }

    @Test
    void donOnlineGiaoHangCoDongPhiShipKhongChiuThue() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = onlineCompleted(a, 1, Fulfilment.DELIVERY, 30_000L);
        InvoiceView inv = issue(o.id());
        assertThat(inv.lines()).hasSize(2);
        var ship = inv.lines().get(1);
        assertThat(ship.itemName()).isEqualTo("Phí vận chuyển");
        assertThat(ship.vatRateBps()).isNull();
        assertThat(ship.vatAmount()).isZero();
        assertThat(ship.lineTotal()).isEqualTo(30_000);
        assertThat(inv.grandTotal()).isEqualTo(o.grandTotal()).isEqualTo(130_000);
        assertThat(inv.vatTotal()).isEqualTo(o.vatTotal());
    }

    @Test
    void moiDonMotHoaDonHieuLucVaGuiLaiCungKhoaTraHoaDonCu() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        UUID key = UUID.randomUUID();
        InvoiceView first = kit.useCases.issue(new InvoiceUseCases.IssueCommand(h.tenant, o.id(), h.user, key));
        InvoiceView replay = kit.useCases.issue(new InvoiceUseCases.IssueCommand(h.tenant, o.id(), h.user, key));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(kit.invoices.all()).hasSize(1);
        assertThat(kit.counters.get("INVOICE:1:C26TAA:" + kit.year()).get()).isEqualTo(1); // không tiêu thêm số
        // khóa khác: đã có hóa đơn hiệu lực
        assertThat(codeOf(() -> issue(o.id()))).isEqualTo(ErrorCode.INVOICE_ALREADY_EXISTS);
        // cùng khóa nhưng đơn khác
        OrderResponse o2 = posOrder(a, 1);
        assertThat(codeOf(() -> kit.useCases.issue(new InvoiceUseCases.IssueCommand(h.tenant, o2.id(), h.user, key))))
                .isEqualTo(ErrorCode.CLIENT_REQUEST_CONFLICT);
    }

    @Test
    void nguoiMuaLayTuYeuCauVaYeuCauChuyenFulfilled() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        kit.requestUseCases.saveByStaff(h.tenant, h.user, o.id(), new BuyerInput(null, "Công ty TNHH A",
                "0123456789-001", "1 Nguyễn Huệ, Quận 1", "ketoan@a.vn"));
        InvoiceView inv = issue(o.id());
        assertThat(inv.buyer().company()).isEqualTo("Công ty TNHH A");
        assertThat(inv.buyer().taxCode()).isEqualTo("0123456789-001");
        assertThat(kit.requestUseCases.get(h.tenant, o.id()).status()).isEqualTo("FULFILLED");
        // yêu cầu đã FULFILLED: sửa phải qua thay thế hóa đơn
        assertThat(codeOf(() -> kit.requestUseCases.saveByStaff(h.tenant, h.user, o.id(),
                new BuyerInput("Khác", null, null, null, null)))).isEqualTo(ErrorCode.INVOICE_ALREADY_EXISTS);
    }

    // ---- hủy, thay thế ----

    @Test
    void huyCanLyDoRoiLapLaiHoaDonMoiKhongDungLaiSoCu() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        InvoiceView first = issue(o.id());
        assertThat(codeOf(() -> kit.useCases.cancel(new InvoiceUseCases.CancelCommand(h.tenant, first.id(), h.user,
                " ")))).isEqualTo(ErrorCode.CANCEL_REASON_REQUIRED);
        InvoiceView cancelled = kit.useCases.cancel(new InvoiceUseCases.CancelCommand(h.tenant, first.id(), h.user,
                "Khách đổi ý"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.invoiceNo()).isEqualTo(1);
        assertThat(codeOf(() -> kit.useCases.cancel(new InvoiceUseCases.CancelCommand(h.tenant, first.id(), h.user,
                "lại")))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThat(kit.auditsOf(SalesAuditPort.Actions.INVOICE_CANCELLED)).hasSize(1);
        // đơn không còn hóa đơn hiệu lực: lập lại được, số mới
        InvoiceView second = issue(o.id());
        assertThat(second.invoiceNo()).isEqualTo(2);
        assertThat(second.kind()).isEqualTo("ORIGINAL");
    }

    @Test
    void thayTheHuyBanGocVaLapBanThayTheTrongMotLan() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 2);
        InvoiceView orig = issue(o.id());
        UUID key = UUID.randomUUID();
        var cmd = new InvoiceUseCases.ReplaceCommand(h.tenant, orig.id(), h.user, "Sai tên công ty",
                new BuyerInput(null, "Công ty B", "0123456789", "2 Hai Bà Trưng", null), key);
        InvoiceView rep = kit.useCases.replace(cmd);
        assertThat(rep.kind()).isEqualTo("REPLACEMENT");
        assertThat(rep.status()).isEqualTo("ISSUED");
        assertThat(rep.originalInvoiceId()).isEqualTo(orig.id());
        assertThat(rep.originalInvoiceNumber()).isEqualTo("00000001");
        assertThat(rep.invoiceNo()).isEqualTo(2);
        assertThat(rep.buyer().company()).isEqualTo("Công ty B");
        assertThat(rep.grandTotal()).isEqualTo(orig.grandTotal());
        assertThat(kit.queries.get(h.tenant, orig.id()).status()).isEqualTo("CANCELLED");
        assertThat(kit.invoices.findEffectiveForOrder(h.tenant, o.id()).orElseThrow().id()).isEqualTo(rep.id());
        assertThat(kit.auditsOf(SalesAuditPort.Actions.INVOICE_REPLACED)).hasSize(1);
        assertThat(kit.auditsOf(SalesAuditPort.Actions.INVOICE_CANCELLED)).hasSize(1);
        // gửi lại cùng khóa: trả bản thay thế cũ, không hủy/lập thêm
        assertThat(kit.useCases.replace(cmd).id()).isEqualTo(rep.id());
        assertThat(kit.invoices.all()).hasSize(2);
        // bản gốc đã hủy không thay thế lại được
        assertThat(codeOf(() -> kit.useCases.replace(new InvoiceUseCases.ReplaceCommand(h.tenant, orig.id(), h.user,
                "x", null, UUID.randomUUID())))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        // thay thế tiếp bản thay thế (kind REPLACEMENT) được, giữ người mua khi không gửi buyer
        InvoiceView rep2 = kit.useCases.replace(new InvoiceUseCases.ReplaceCommand(h.tenant, rep.id(), h.user,
                "Sửa địa chỉ", null, UUID.randomUUID()));
        assertThat(rep2.buyer().company()).isEqualTo("Công ty B");
        assertThat(rep2.originalInvoiceId()).isEqualTo(rep.id());
        assertThat(rep2.invoiceNo()).isEqualTo(3);
    }

    @Test
    void thayTheVoiMstSaiBiTuChoiVaGiuNguyenBanGoc() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        InvoiceView orig = issue(o.id());
        assertThat(codeOf(() -> kit.useCases.replace(new InvoiceUseCases.ReplaceCommand(h.tenant, orig.id(), h.user,
                "sai", new BuyerInput(null, "Cty", "123", "đc", null), UUID.randomUUID()))))
                .isEqualTo(ErrorCode.INVALID_TAX_CODE);
        assertThat(kit.queries.get(h.tenant, orig.id()).status()).isEqualTo("ISSUED");
        assertThat(kit.invoices.all()).hasSize(1);
    }

    // ---- điều chỉnh khi trả hàng ----

    private CreateReturnCommand ret(OrderResponse o, int qty) {
        return new CreateReturnCommand(h.tenant, h.user, o.id(), List.of(new CreateReturnCommand.Line(
                o.lines().get(0).id(), qty, Disposition.RESTOCK)), "Sách lỗi", null, UUID.randomUUID());
    }

    @Test
    void traHangSinhHoaDonDieuChinhGiamTrongCungLuotVaTongKhopGoc() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 33_335, null, 10);
        OrderResponse o = posOrder(a, 3); // 100.005 đã gồm VAT 5%
        InvoiceView orig = issue(o.id());
        long origVat = orig.vatTotal();

        var r1 = h.tx(() -> kit.createReturn.create(ret(o, 1)));
        var adjustments = kit.invoices.findActiveAdjustments(h.tenant, orig.id());
        assertThat(adjustments).hasSize(1);
        var adj1 = adjustments.get(0);
        assertThat(adj1.kind()).isEqualTo(InvoiceKind.ADJUSTMENT_DECREASE);
        assertThat(adj1.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(adj1.returnId()).isEqualTo(r1.id());
        assertThat(adj1.originalInvoiceId()).isEqualTo(orig.id());
        assertThat(adj1.grandTotal().amount()).isEqualTo(r1.refundTotal()).isEqualTo(33_335);
        assertThat(adj1.invoiceNo()).isEqualTo(2);
        assertThat(adj1.seller().taxCode()).isEqualTo("0123456789");
        assertThat(kit.auditsOf(SalesAuditPort.Actions.INVOICE_ADJUSTED)).hasSize(1);

        var r2 = h.tx(() -> kit.createReturn.create(ret(o, 2)));
        var all = kit.invoices.findActiveAdjustments(h.tenant, orig.id());
        assertThat(all).hasSize(2);
        long adjTotal = all.stream().mapToLong(i -> i.grandTotal().amount()).sum();
        long adjVat = all.stream().mapToLong(i -> i.vatTotal().amount()).sum();
        assertThat(adjTotal).as("tổng điều chỉnh không vượt hóa đơn gốc, trả hết thì bằng gốc").isEqualTo(orig.grandTotal());
        assertThat(adjVat).as("trả hết thì tổng thuế điều chỉnh đúng bằng thuế gốc").isEqualTo(origVat);
        assertThat(r1.refundTotal() + r2.refundTotal()).isEqualTo(orig.grandTotal());
        // hóa đơn gốc bất biến, hiển thị danh sách điều chỉnh
        InvoiceView detail = kit.queries.get(h.tenant, orig.id());
        assertThat(detail.status()).isEqualTo("ISSUED");
        assertThat(detail.adjustments()).hasSize(2);
        // đã có điều chỉnh: không hủy, không thay thế
        assertThat(codeOf(() -> kit.useCases.cancel(new InvoiceUseCases.CancelCommand(h.tenant, orig.id(), h.user,
                "x")))).isEqualTo(ErrorCode.INVOICE_HAS_ADJUSTMENTS);
        assertThat(codeOf(() -> kit.useCases.replace(new InvoiceUseCases.ReplaceCommand(h.tenant, orig.id(), h.user,
                "x", null, UUID.randomUUID())))).isEqualTo(ErrorCode.INVOICE_HAS_ADJUSTMENTS);
    }

    @Test
    void donKhongCoHoaDonTraHangKhongSinhGiHetHanhViCu() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 2);
        var r = h.tx(() -> kit.createReturn.create(ret(o, 1)));
        assertThat(r.refundTotal()).isEqualTo(100_000);
        assertThat(kit.invoices.all()).isEmpty();
        assertThat(kit.counterNames).isEmpty();
        assertThat(kit.auditsOf(SalesAuditPort.Actions.INVOICE_ADJUSTED)).isEmpty();
        assertThat(h.item(a).onHand()).isEqualTo(9); // việc trả hàng vẫn nhập lại tồn
    }

    @Test
    void daCoPhieuTraTruocKhiLapHoaDonThiKhongLap() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 2);
        h.tx(() -> kit.createReturn.create(ret(o, 1)));
        assertThat(codeOf(() -> issue(o.id()))).isEqualTo(ErrorCode.ORDER_NOT_INVOICEABLE);
    }

    // ---- tự phát hành ----

    private PaymentIntentUseCases intentsWithAutoIssue() {
        return new PaymentIntentUseCases(h.intents, h.orderRepo, h.stockService, h.auditPort, h.clock)
                .withInvoiceAutoIssue(kit.autoIssue);
    }

    private lms.sales.ordering.application.PosOrderResult posAwaiting(UUID title) {
        return h.tx(() -> h.placePos.createWithPayment(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, 1, null)), PaymentMethod.CASH, null, null, null, null,
                UUID.randomUUID())));
    }

    @Test
    void tuPhatHanhKhiXacNhanThuNeuBatCoVaCoYeuCau() {
        kit.configureSeller(true);
        UUID a = h.book("Sách A", 100_000, null, 10);
        var posted = posAwaiting(a);
        UUID orderId = posted.order().id();
        // POS nhập thông tin người mua khi đơn còn chờ thu (đơn AWAITING_PAYMENT vẫn nhận yêu cầu)
        kit.requestUseCases.saveByStaff(h.tenant, h.user, orderId, new BuyerInput("Nguyễn Văn An", null, null, null,
                "an@x.vn"));
        assertThat(kit.invoices.all()).isEmpty();
        h.tx(() -> intentsWithAutoIssue().confirm(h.tenant, h.user, posted.paymentIntent().id(),
                new PaymentIntentUseCases.ConfirmCommand(null, null)));
        var inv = kit.invoices.findEffectiveForOrder(h.tenant, orderId).orElseThrow();
        assertThat(inv.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(inv.buyer().name()).isEqualTo("Nguyễn Văn An");
        assertThat(inv.issuedBy()).isEqualTo(h.user);
    }

    @Test
    void khongTuPhatHanhKhiTatCoHoacKhongCoYeuCau() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        // 1) cờ tắt, có yêu cầu
        kit.configureSeller(false);
        var p1 = posAwaiting(a);
        kit.requestUseCases.saveByStaff(h.tenant, h.user, p1.order().id(), new BuyerInput("An", null, null, null, null));
        h.tx(() -> intentsWithAutoIssue().confirm(h.tenant, h.user, p1.paymentIntent().id(),
                new PaymentIntentUseCases.ConfirmCommand(null, null)));
        assertThat(kit.invoices.all()).isEmpty();
        // 2) cờ bật, không có yêu cầu
        kit.configureSeller(true);
        var p2 = posAwaiting(a);
        h.tx(() -> intentsWithAutoIssue().confirm(h.tenant, h.user, p2.paymentIntent().id(),
                new PaymentIntentUseCases.ConfirmCommand(null, null)));
        assertThat(kit.invoices.all()).isEmpty();
    }

    @Test
    void thieuCauHinhNguoiBanKhongLamHongViecXacNhanThu() {
        // cờ bật nhưng cấu hình người bán chưa đủ: bỏ qua tự phát hành, thanh toán vẫn thành công
        kit.settings.save(lms.sales.invoicing.domain.InvoiceSettings.restore(h.tenant, null, null, null, null, null,
                "1", null, true, 0, null, null));
        UUID a = h.book("Sách A", 100_000, null, 10);
        var p = posAwaiting(a);
        kit.requestUseCases.saveByStaff(h.tenant, h.user, p.order().id(), new BuyerInput("An", null, null, null, null));
        var resp = h.tx(() -> intentsWithAutoIssue().confirm(h.tenant, h.user, p.paymentIntent().id(),
                new PaymentIntentUseCases.ConfirmCommand(null, null)));
        assertThat(resp.status().name()).isEqualTo("PAID");
        assertThat(kit.invoices.all()).isEmpty();
    }

    @Test
    void tuPhatHanhKhiHoanTatDonOnlineCoYeuCau() {
        kit.configureSeller(true);
        UUID a = h.book("Sách A", 100_000, null, 10);
        var placed = h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER,
                new Recipient("An", "0912345678", null), UUID.randomUUID())));
        UUID id = placed.order().id();
        kit.requestUseCases.saveByCustomer(h.tenant, customer, id, new BuyerInput("An", null, null, null, null));
        var wf = new lms.sales.ordering.application.OrderWorkflowUseCases(h.orderRepo, h.refundRepo, h.stockService,
                h.getSettings, h.auditPort, h.clock).withInvoiceAutoIssue(kit.autoIssue);
        h.tx(() -> wf.confirm(h.tenant, h.user, id, null, null));
        h.tx(() -> wf.markPaid(h.tenant, h.user, id, PaymentMethod.BANK_TRANSFER, "x", null));
        h.tx(() -> wf.markReadyForPickup(h.tenant, h.user, id, null));
        assertThat(kit.invoices.all()).isEmpty();
        h.tx(() -> wf.complete(h.tenant, h.user, id, null));
        assertThat(kit.invoices.findEffectiveForOrder(h.tenant, id)).isPresent();
    }

    // ---- yêu cầu của khách (IDOR) ----

    @Test
    void khachChiYeuCauChoDonOnlineCuaMinhVaLoiKhongLoSuTonTai() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        var placed = h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(a, 1)), Fulfilment.PICKUP, PaymentMethod.BANK_TRANSFER,
                new Recipient("An", "0912345678", null), UUID.randomUUID())));
        UUID id = placed.order().id();
        var buyer = new BuyerInput("Nguyễn Văn An", null, null, null, "an@x.vn");

        var mine = kit.requestUseCases.saveByCustomer(h.tenant, customer, id, buyer);
        assertThat(mine.status()).isEqualTo("OPEN");
        assertThat(mine.source()).isEqualTo("ONLINE");

        SalesException other = assertThrows(SalesException.class,
                () -> kit.requestUseCases.saveByCustomer(h.tenant, otherCustomer, id, buyer));
        SalesException missing = assertThrows(SalesException.class,
                () -> kit.requestUseCases.saveByCustomer(h.tenant, customer, UUID.randomUUID(), buyer));
        assertThat(other.code()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(other.code()).isEqualTo(missing.code());
        assertThat(other.getMessage()).isEqualTo(missing.getMessage()); // cùng một thân
        // đơn POS không phải đơn online của khách nào: cũng 404
        OrderResponse pos = posOrder(a, 1);
        assertThat(codeOf(() -> kit.requestUseCases.saveByCustomer(h.tenant, customer, pos.id(), buyer)))
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        // yêu cầu của người khác không bị ghi đè
        assertThat(kit.requestUseCases.get(h.tenant, id).buyerName()).isEqualTo("Nguyễn Văn An");
        // khách sửa lại yêu cầu của mình khi còn OPEN
        var again = kit.requestUseCases.saveByCustomer(h.tenant, customer, id, new BuyerInput("Trần B", null, null,
                null, null));
        assertThat(again.buyerName()).isEqualTo("Trần B");
        assertThat(kit.auditsOf(SalesAuditPort.Actions.INVOICE_REQUEST_SAVED)).hasSize(2);
    }

    @Test
    void yeuCauKhongVaoAuditVaKiemTraDuLieuNguoiMua() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o = posOrder(a, 1);
        kit.requestUseCases.saveByStaff(h.tenant, h.user, o.id(), new BuyerInput(null, "Công ty Z", "0123456789",
                "9 Pasteur", "z@z.vn"));
        var audit = kit.auditsOf(SalesAuditPort.Actions.INVOICE_REQUEST_SAVED).get(0);
        assertThat(audit.data().toString()).doesNotContain("Công ty Z").doesNotContain("9 Pasteur")
                .doesNotContain("z@z.vn").doesNotContain("0123456789");
        assertThat(audit.data()).containsEntry("hasTaxCode", true);
        // thiếu cả tên cá nhân lẫn tên đơn vị
        assertThat(codeOf(() -> kit.requestUseCases.saveByStaff(h.tenant, h.user, o.id(),
                new BuyerInput(null, null, null, null, "z@z.vn")))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(codeOf(() -> kit.requestUseCases.saveByStaff(h.tenant, h.user, o.id(),
                new BuyerInput("A", "Cty", "12345", "đc", null)))).isEqualTo(ErrorCode.INVALID_TAX_CODE);
        assertThat(codeOf(() -> kit.requestUseCases.get(h.tenant, UUID.randomUUID()))).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    // ---- cấu hình ----

    @Test
    void capNhatCauHinhKiemPhienBanVaGhiAudit() {
        var saved = kit.settingsUseCases.update(new lms.sales.invoicing.application.InvoiceSettingsUseCases.UpdateCommand(
                h.tenant, h.user, "Nhà sách A", "0123456789", "1 Lê Lợi", "0900", "a@a.vn", "1", "C26TAA", false, 0));
        assertThat(saved.complete()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(kit.auditsOf(SalesAuditPort.Actions.INVOICE_SETTINGS_CHANGED)).hasSize(1);
        assertThat(codeOf(() -> kit.settingsUseCases.update(new lms.sales.invoicing.application.InvoiceSettingsUseCases
                .UpdateCommand(h.tenant, h.user, "Nhà sách A", "0123456789", "1 Lê Lợi", null, null, "1", "C26TAA",
                false, 0)))).isEqualTo(ErrorCode.SETTINGS_VERSION_CONFLICT);
        assertThat(codeOf(() -> kit.settingsUseCases.update(new lms.sales.invoicing.application.InvoiceSettingsUseCases
                .UpdateCommand(h.tenant, h.user, "Nhà sách A", "ABC", "1 Lê Lợi", null, null, "1", "C26TAA", false, 1))))
                .isEqualTo(ErrorCode.INVALID_TAX_CODE);
        assertThat(kit.settingsUseCases.get(UUID.randomUUID()).complete()).isFalse();
    }

    // ---- danh sách, bản in ----

    @Test
    void timKiemLocTheoTrangThaiVaLoaiVaBanInCoCanhBao() {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 10);
        OrderResponse o1 = posOrder(a, 1);
        OrderResponse o2 = posOrder(a, 1);
        InvoiceView i1 = issue(o1.id());
        issue(o2.id());
        kit.useCases.cancel(new InvoiceUseCases.CancelCommand(h.tenant, i1.id(), h.user, "x"));
        var cancelled = kit.queries.search(h.tenant, new InvoiceQueryUseCases.SearchQuery(InvoiceStatus.CANCELLED, null,
                null, null, null, 0, 20));
        assertThat(cancelled.total()).isEqualTo(1);
        assertThat(kit.queries.search(h.tenant, new InvoiceQueryUseCases.SearchQuery(null, InvoiceKind.ORIGINAL, null,
                null, null, 0, 20)).total()).isEqualTo(2);
        var printed = kit.queries.print(h.tenant, i1.id());
        String html = new String(printed.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(printed.contentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(html).contains("HÓA ĐƠN ĐÃ HỦY").contains("Hóa đơn nội bộ, không thay thế hóa đơn điện tử");
        assertThat(codeOf(() -> kit.queries.get(h.tenant, UUID.randomUUID()))).isEqualTo(ErrorCode.INVOICE_NOT_FOUND);
    }

    // ---- đồng thời ----

    /**
     * Mức logic: 20 luồng phát hành song song cho 20 đơn khác nhau không trùng số, liên tục từ 1 (bộ đếm giả thread-safe
     * và kho giả đồng bộ). Khóa dòng bộ đếm thật và chỉ mục duy nhất của Postgres được kiểm ở InvoicingIT.
     */
    @Test
    void phatHanhSongSongKhongTrungSo() throws Exception {
        kit.configureSeller(false);
        UUID a = h.book("Sách A", 100_000, null, 100);
        int n = 20;
        List<UUID> orderIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            orderIds.add(posOrder(a, 1).id());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (UUID id : orderIds) {
            futures.add(pool.submit(() -> {
                start.await();
                return issue(id).invoiceNo();
            }));
        }
        start.countDown();
        Set<Long> numbers = new TreeSet<>();
        for (Future<Long> f : futures) {
            numbers.add(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(numbers).hasSize(n);
        assertThat(numbers.iterator().next()).isEqualTo(1L);
        assertThat(((TreeSet<Long>) numbers).last()).isEqualTo((long) n);
    }
}
