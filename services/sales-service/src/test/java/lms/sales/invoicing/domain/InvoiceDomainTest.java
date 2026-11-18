package lms.sales.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

/** Máy trạng thái DRAFT, ISSUED, CANCELLED; bất biến sau ISSUED; hủy, thay thế, điều chỉnh (ADR-0030 D1). */
class InvoiceDomainTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-19T03:00:00Z");
    private static final SellerInfo SELLER = new SellerInfo("Nhà sách A", "0123456789", "1 Lê Lợi", null, null);

    /** 105.000 đã gồm VAT 5%: trước thuế 100.000, thuế 5.000. */
    static InvoiceLine book(int no, UUID orderLine) {
        return new InvoiceLine(no, orderLine, "Sách A", "ISBN-1", "cuốn", 1, Money.of(105_000), Money.ZERO,
                Money.of(100_000), 500, Money.of(5_000), Money.of(105_000));
    }

    static Invoice draft(UUID orderId, UUID orderLine) {
        return Invoice.draftOriginal(UUID.randomUUID(), TENANT, orderId, "DH-000001", "1", "C26TAA", SELLER,
                BuyerInfo.notRequested(), true, "CASH", List.of(book(1, orderLine), InvoiceLine.shipping(2, Money.of(20_000))),
                ACTOR, NOW, UUID.randomUUID());
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void nhapChuaCoSoRoiPhatHanhGanSoNamVaSoTienBangChu() {
        Invoice i = draft(UUID.randomUUID(), UUID.randomUUID());
        assertThat(i.status()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(i.invoiceNo()).isNull();
        assertThat(i.displayNumber()).isNull();
        assertThat(i.amountInWords()).isNull();
        // tổng tính từ dòng: phí ship là dòng không chịu thuế
        assertThat(i.totalBeforeVat().amount()).isEqualTo(120_000);
        assertThat(i.vatTotal().amount()).isEqualTo(5_000);
        assertThat(i.grandTotal().amount()).isEqualTo(125_000);

        i.issue(7, 2026, null, ACTOR, NOW);
        assertThat(i.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(i.isEffective()).isTrue();
        assertThat(i.invoiceNo()).isEqualTo(7);
        assertThat(i.displayNumber()).isEqualTo("00000007");
        assertThat(i.fiscalYear()).isEqualTo(2026);
        assertThat(i.issuer()).isEqualTo(Invoice.ISSUER_INTERNAL);
        assertThat(i.amountInWords()).isEqualTo("Một trăm hai mươi lăm nghìn đồng");
        assertThat(i.issuedAt()).isEqualTo(NOW);
    }

    @Test
    void phatHanhHaiLanHoacHuyKhiChuaPhatHanhBiTuChoi() {
        Invoice i = draft(UUID.randomUUID(), UUID.randomUUID());
        assertThat(codeOf(() -> i.cancel("sai", ACTOR, NOW, false))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        i.issue(1, 2026, null, ACTOR, NOW);
        assertThat(codeOf(() -> i.issue(2, 2026, null, ACTOR, NOW))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThat(i.invoiceNo()).isEqualTo(1); // số không bị ghi đè
    }

    @Test
    void huyCanLyDoVaChiTuDaPhatHanh() {
        Invoice i = draft(UUID.randomUUID(), UUID.randomUUID());
        i.issue(1, 2026, null, ACTOR, NOW);
        assertThat(codeOf(() -> i.cancel(null, ACTOR, NOW, false))).isEqualTo(ErrorCode.CANCEL_REASON_REQUIRED);
        assertThat(codeOf(() -> i.cancel("   ", ACTOR, NOW, false))).isEqualTo(ErrorCode.CANCEL_REASON_REQUIRED);
        assertThat(codeOf(() -> i.cancel("x".repeat(501), ACTOR, NOW, false))).isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThat(i.status()).isEqualTo(InvoiceStatus.ISSUED);

        i.cancel("  Sai tên người mua  ", ACTOR, NOW.plusSeconds(60), false);
        assertThat(i.status()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(i.isEffective()).isFalse();
        assertThat(i.cancelReason()).isEqualTo("Sai tên người mua");
        assertThat(i.cancelledBy()).isEqualTo(ACTOR);
        assertThat(i.cancelledAt()).isEqualTo(NOW.plusSeconds(60));
        // số và tiền giữ nguyên để đối chiếu
        assertThat(i.invoiceNo()).isEqualTo(1);
        assertThat(i.grandTotal().amount()).isEqualTo(125_000);
        // hủy lần hai
        assertThat(codeOf(() -> i.cancel("lại", ACTOR, NOW, false))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
    }

    @Test
    void daCoDieuChinhThiKhongHuyDuoc() {
        Invoice i = draft(UUID.randomUUID(), UUID.randomUUID());
        i.issue(1, 2026, null, ACTOR, NOW);
        assertThat(codeOf(() -> i.cancel("sai", ACTOR, NOW, true))).isEqualTo(ErrorCode.INVOICE_HAS_ADJUSTMENTS);
        assertThat(i.status()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    void boHoaDonNhapChuyenCancelledKemLyDoKhongXoa() {
        Invoice i = draft(UUID.randomUUID(), UUID.randomUUID());
        assertThat(codeOf(() -> i.abandonDraft(" ", ACTOR, NOW))).isEqualTo(ErrorCode.CANCEL_REASON_REQUIRED);
        i.abandonDraft("Nhập nhầm", ACTOR, NOW);
        assertThat(i.status()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(i.invoiceNo()).isNull();
        assertThat(codeOf(() -> i.issue(1, 2026, null, ACTOR, NOW))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
    }

    /** Bất biến ở domain: không có setter; thao tác ghi duy nhất là issue, cancel, abandonDraft, attachExternal. */
    @Test
    void khongCoSetterVaChiCoBonThaoTacGhi() {
        List<String> voids = Arrays.stream(Invoice.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == void.class).map(Method::getName).sorted().toList();
        assertThat(voids).containsExactly("abandonDraft", "attachExternal", "cancel", "issue");
        assertThat(Arrays.stream(Invoice.class.getDeclaredMethods()).map(Method::getName)
                .filter(n -> n.startsWith("set"))).isEmpty();
        // lines() không sửa được từ ngoài
        Invoice i = draft(UUID.randomUUID(), UUID.randomUUID());
        List<InvoiceLine> lines = i.lines();
        assertThrows(UnsupportedOperationException.class, () -> lines.add(book(9, null)));
    }

    @Test
    void tongKhongKhopSnapshotBiPhatHienKhiKhoiPhuc() {
        Invoice i = draft(UUID.randomUUID(), UUID.randomUUID());
        Invoice.Snapshot s = new Invoice.Snapshot(i.id(), TENANT, i.kind(), i.status(), i.orderId(), i.orderNo(), null,
                null, "1", "C26TAA", null, null, null, null, null, null, null, SELLER, i.buyer(), true, "CASH",
                120_000, 5_000, 124_999, null, "INTERNAL", null, null, null, null, ACTOR, NOW, i.lines(), 0);
        assertThrows(IllegalStateException.class, () -> Invoice.restore(s));
    }

    @Test
    void thayTheSaoDongVaGanHoaDonGoc() {
        Invoice orig = draft(UUID.randomUUID(), UUID.randomUUID());
        orig.issue(1, 2026, null, ACTOR, NOW);
        orig.cancel("Sai thông tin", ACTOR, NOW, false);
        BuyerInfo buyer = BuyerInfo.of(null, "Công ty A", "0123456789", "1 Nguyễn Huệ", null);
        Invoice rep = Invoice.draftReplacement(UUID.randomUUID(), orig, "1", "C26TAA", SELLER, buyer, ACTOR, NOW,
                UUID.randomUUID());
        assertThat(rep.kind()).isEqualTo(InvoiceKind.REPLACEMENT);
        assertThat(rep.originalInvoiceId()).isEqualTo(orig.id());
        assertThat(rep.orderId()).isEqualTo(orig.orderId());
        assertThat(rep.lines()).isEqualTo(orig.lines());
        assertThat(rep.grandTotal()).isEqualTo(orig.grandTotal());
        assertThat(rep.buyer().company()).isEqualTo("Công ty A");
        assertThat(rep.status()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    void dieuChinhGanGocVaPhieuTraKhongHuyDuoc() {
        Invoice orig = draft(UUID.randomUUID(), UUID.randomUUID());
        orig.issue(1, 2026, null, ACTOR, NOW);
        UUID returnId = UUID.randomUUID();
        Invoice adj = Invoice.draftAdjustment(UUID.randomUUID(), orig, returnId,
                List.of(new InvoiceLine(1, orig.lines().get(0).orderLineId(), "Sách A", "ISBN-1", "cuốn", 1,
                        Money.of(105_000), Money.ZERO, Money.of(100_000), 500, Money.of(5_000), Money.of(105_000))),
                ACTOR, NOW);
        assertThat(adj.kind()).isEqualTo(InvoiceKind.ADJUSTMENT_DECREASE);
        assertThat(adj.originalInvoiceId()).isEqualTo(orig.id());
        assertThat(adj.returnId()).isEqualTo(returnId);
        assertThat(adj.seller()).isEqualTo(orig.seller()); // theo hóa đơn gốc, không theo cấu hình hiện tại
        adj.issue(2, 2026, null, ACTOR, NOW);
        assertThat(codeOf(() -> adj.cancel("x", ACTOR, NOW, false))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
    }

    @Test
    void dongHoaDonGiuBatBienSoHoc() {
        // thành tiền khác trước thuế cộng thuế
        assertThrows(IllegalArgumentException.class, () -> new InvoiceLine(1, null, "A", null, "cuốn", 1, Money.of(1),
                Money.ZERO, Money.of(100), 500, Money.of(5), Money.of(106)));
        // dòng không chịu thuế mà có tiền thuế
        assertThrows(IllegalArgumentException.class, () -> new InvoiceLine(1, null, "A", null, "cuốn", 1, Money.of(1),
                Money.ZERO, Money.of(100), null, Money.of(5), Money.of(105)));
        // số lượng không dương
        assertThrows(IllegalArgumentException.class, () -> new InvoiceLine(1, null, "A", null, "cuốn", 0, Money.of(1),
                Money.ZERO, Money.of(100), 500, Money.of(5), Money.of(105)));
        InvoiceLine ship = InvoiceLine.shipping(3, Money.of(30_000));
        assertThat(ship.vatRateBps()).isNull();
        assertThat(ship.vatAmount().isZero()).isTrue();
        assertThat(ship.lineTotal().amount()).isEqualTo(30_000);
        assertThat(ship.orderLineId()).isNull();
    }
}
