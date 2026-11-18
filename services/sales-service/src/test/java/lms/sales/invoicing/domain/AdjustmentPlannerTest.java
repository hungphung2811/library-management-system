package lms.sales.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rounding;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

/** VAT của hóa đơn điều chỉnh: công thức ADR (round(hoàn x thuếGốc / thànhTiềnGốc)), lũy kế qua nhiều phiếu, không vượt gốc. */
class AdjustmentPlannerTest {

    private static final UUID L1 = UUID.randomUUID();
    private static final UUID L2 = UUID.randomUUID();

    /** Dòng gốc: 3 cuốn x 33.335 = 100.005 (đã gồm VAT 5%): thuế round(100.005 x 500 / 10.500) = 4.762. */
    private static InvoiceLine orig(UUID id, int qty, long total, long vat) {
        return new InvoiceLine(1, id, "Sách", "I", "cuốn", qty, Money.of(total / qty), Money.ZERO,
                Money.of(total - vat), 500, Money.of(vat), Money.of(total));
    }

    @Test
    void phieuTraDauTienDungCongThucAdr() {
        InvoiceLine o = orig(L1, 3, 100_005, 4_762);
        long refund = 66_670; // 2 cuốn
        List<InvoiceLine> out = AdjustmentPlanner.plan(List.of(o), Map.of(),
                List.of(new AdjustmentPlanner.Returned(L1, 2, refund)), true);
        long expectVat = Rounding.mulDivHalfUp(refund, 4_762, 100_005);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).vatAmount().amount()).isEqualTo(expectVat);
        assertThat(out.get(0).amountBeforeVat().amount()).isEqualTo(refund - expectVat);
        assertThat(out.get(0).lineTotal().amount()).isEqualTo(refund);
        assertThat(out.get(0).quantity()).isEqualTo(2);
        assertThat(out.get(0).vatRateBps()).isEqualTo(500);
        assertThat(out.get(0).orderLineId()).isEqualTo(L1);
    }

    @Test
    void traHetQuaNhieuPhieuTongThueDieuChinhBangDungThueGoc() {
        // Làm tròn từng phiếu độc lập có thể lệch 1 đồng so với thuế gốc; lũy kế thì khớp đúng
        InvoiceLine o = orig(L1, 3, 100_005, 4_762);
        long[] refunds = {33_335, 33_335, 33_335};
        long naive = 0;
        long cumVat = 0;
        long prior = 0;
        int qty = 0;
        for (long r : refunds) {
            naive += Rounding.mulDivHalfUp(r, 4_762, 100_005);
            List<InvoiceLine> out = AdjustmentPlanner.plan(List.of(o), Map.of(L1, new AdjustmentPlanner.Prior(qty, prior)),
                    List.of(new AdjustmentPlanner.Returned(L1, 1, r)), true);
            cumVat += out.get(0).vatAmount().amount();
            prior += r;
            qty++;
        }
        assertThat(cumVat).as("lũy kế khớp thuế dòng gốc").isEqualTo(4_762);
        assertThat(naive).as("cách làm tròn độc lập lệch 1 đồng").isEqualTo(4_761);
    }

    @Test
    void moiPhieuTongTienBangTienHoanVaKhongAm() {
        InvoiceLine o = orig(L1, 10, 1_050, 50);
        long prior = 0;
        long sumVat = 0;
        for (int i = 0; i < 10; i++) {
            long r = 105;
            var l = AdjustmentPlanner.plan(List.of(o), Map.of(L1, new AdjustmentPlanner.Prior(i, prior)),
                    List.of(new AdjustmentPlanner.Returned(L1, 1, r)), true).get(0);
            assertThat(l.amountBeforeVat().amount() + l.vatAmount().amount()).isEqualTo(r);
            assertThat(l.vatAmount().amount()).isBetween(0L, r);
            sumVat += l.vatAmount().amount();
            prior += r;
        }
        assertThat(sumVat).isEqualTo(50);
    }

    @Test
    void vuotSoLuongHoacGiaTriGocBiTuChoi() {
        InvoiceLine o = orig(L1, 3, 100_005, 4_762);
        assertThat(codeOf(() -> AdjustmentPlanner.plan(List.of(o), Map.of(),
                List.of(new AdjustmentPlanner.Returned(L1, 4, 100_005)), true))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThat(codeOf(() -> AdjustmentPlanner.plan(List.of(o), Map.of(),
                List.of(new AdjustmentPlanner.Returned(L1, 3, 100_006)), true))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        // tính cả phần đã điều chỉnh trước đó
        assertThat(codeOf(() -> AdjustmentPlanner.plan(List.of(o), Map.of(L1, new AdjustmentPlanner.Prior(2, 66_670)),
                List.of(new AdjustmentPlanner.Returned(L1, 2, 33_335)), true))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
        assertThat(codeOf(() -> AdjustmentPlanner.plan(List.of(o), Map.of(L1, new AdjustmentPlanner.Prior(1, 90_000)),
                List.of(new AdjustmentPlanner.Returned(L1, 1, 20_000)), true))).isEqualTo(ErrorCode.INVOICE_STATE_CONFLICT);
    }

    @Test
    void dongLaHoacKhongCoTienHoan() {
        InvoiceLine o = orig(L1, 3, 100_005, 4_762);
        assertThat(codeOf(() -> AdjustmentPlanner.plan(List.of(o), Map.of(),
                List.of(new AdjustmentPlanner.Returned(UUID.randomUUID(), 1, 1_000)), true)))
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        // dòng hoàn 0 đồng bị bỏ qua; hết dòng thì lỗi (use case lọc trước nên không gọi)
        assertThat(codeOf(() -> AdjustmentPlanner.plan(List.of(o), Map.of(),
                List.of(new AdjustmentPlanner.Returned(L1, 1, 0)), true))).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void dongKhongChiuThueKhongSinhThueVaNhieuDongDanhSoLai() {
        InvoiceLine zeroTax = new InvoiceLine(1, L2, "Sách KCT", null, "cuốn", 2, Money.of(10_000), Money.ZERO,
                Money.of(20_000), null, Money.ZERO, Money.of(20_000));
        InvoiceLine taxed = orig(L1, 1, 105_000, 5_000);
        List<InvoiceLine> out = AdjustmentPlanner.plan(List.of(taxed, zeroTax), Map.of(),
                List.of(new AdjustmentPlanner.Returned(L2, 1, 10_000), new AdjustmentPlanner.Returned(L1, 1, 105_000)),
                true);
        assertThat(out).extracting(InvoiceLine::lineNo).containsExactly(1, 2);
        assertThat(out.get(0).vatAmount().isZero()).isTrue();
        assertThat(out.get(0).vatRateBps()).isNull();
        assertThat(out.get(1).vatAmount().amount()).isEqualTo(5_000);
    }

    @Test
    void giamGiaDonViHienThiKhopTienHoan() {
        // giá gồm thuế: đơn giá 40.000 x 2 hoàn 70.000 thì giảm giá 10.000; giá chưa gồm thuế so với tiền trước thuế
        InvoiceLine o = new InvoiceLine(1, L1, "Sách", "I", "cuốn", 2, Money.of(40_000), Money.of(10_000),
                Money.of(66_667), 500, Money.of(3_333), Money.of(70_000));
        var incl = AdjustmentPlanner.plan(List.of(o), Map.of(), List.of(new AdjustmentPlanner.Returned(L1, 2, 70_000)),
                true).get(0);
        assertThat(incl.discount().amount()).isEqualTo(10_000);
        var excl = AdjustmentPlanner.plan(List.of(o), Map.of(), List.of(new AdjustmentPlanner.Returned(L1, 2, 70_000)),
                false).get(0);
        assertThat(excl.discount().amount()).isEqualTo(80_000 - excl.amountBeforeVat().amount());
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }
}
