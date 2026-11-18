package lms.demo.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.stream.Collectors;
import lms.demo.plan.DemoPlan.Channel;
import lms.demo.plan.DemoPlan.OrderStatus;
import org.junit.jupiter.api.Test;

/** Chi tiết bán hàng của kế hoạch demo: SKU, nhà cung cấp, giá nhập, vị trí kho, ghi chú đơn (tất định, không đổi phần khác). */
class DemoPlanSalesDetailsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);

    private final DemoPlan plan = DemoPlan.generate(TODAY, DemoPlan.DEFAULT_SEED);

    @Test
    void skuDuyNhatDungDinhDangVaTheoNamXuatBan() {
        assertThat(plan.stockItems().stream().map(DemoPlan.StockItemPlan::sku).collect(Collectors.toSet()))
                .hasSameSizeAs(plan.stockItems());
        for (DemoPlan.StockItemPlan s : plan.stockItems()) {
            int year = plan.titles().stream().filter(t -> t.id().equals(s.titleId())).findFirst().orElseThrow()
                    .publishYear();
            assertThat(s.sku()).isEqualTo(String.format("LMS-%d-%04d", year, s.index() + 1));
            assertThat(s.sku().length()).isLessThanOrEqualTo(40);
        }
    }

    @Test
    void giaNhapTuNamMuoiLamDenBayMuoiPhanTramGiaBanVaKhopPhieuNhap() {
        for (DemoPlan.StockItemPlan s : plan.stockItems()) {
            double ratio = (double) s.costPrice() / s.unitPrice();
            assertThat(ratio).as("mặt hàng %d", s.index()).isBetween(0.54, 0.71);
            assertThat(s.costPrice()).isLessThan(s.unitPrice()).isPositive();
            long receiptCost = plan.receipts().get(s.receiptIndex()).lines().stream()
                    .filter(l -> l.titleId().equals(s.titleId())).findFirst().orElseThrow().unitCost();
            assertThat(receiptCost).isEqualTo(s.costPrice());
        }
    }

    @Test
    void nhaCungCapViTriKhoVaSoLuongDatLai() {
        assertThat(plan.stockItems().stream().map(DemoPlan.StockItemPlan::supplierName).collect(Collectors.toSet()))
                .contains("Fahasa", "Nhà sách Phương Nam", "Nhã Nam", "First News - Trí Việt");
        assertThat(plan.stockItems()).allSatisfy(s -> {
            assertThat(s.binLocation()).matches("K[1-4]-[A-D][1-4]");
            assertThat(s.binLocation().length()).isLessThanOrEqualTo(64);
            assertThat(s.reorderQuantity()).isPositive();
            assertThat(s.supplierName().length()).isLessThanOrEqualTo(255);
            if (s.note() != null) {
                assertThat(s.note().length()).isLessThanOrEqualTo(500);
            }
        });
        assertThat(plan.stockItems().stream().map(DemoPlan.StockItemPlan::binLocation).collect(Collectors.toSet()))
                .hasSameSizeAs(plan.stockItems());
    }

    @Test
    void ghiChuDonHopLyVaNgayGiaoChiChoDonOnlineKhongHuy() {
        assertThat(plan.orders().stream().filter(o -> o.customerNote() != null)).hasSizeGreaterThan(10);
        assertThat(plan.orders().stream().filter(o -> o.staffNote() != null)).hasSizeGreaterThan(10);
        for (DemoPlan.OrderPlan o : plan.orders()) {
            if (o.customerNote() != null) assertThat(o.customerNote().length()).isLessThanOrEqualTo(500);
            if (o.staffNote() != null) assertThat(o.staffNote().length()).isLessThanOrEqualTo(500);
            if (o.expectedDeliveryDays() != null) {
                assertThat(o.channel()).isEqualTo(Channel.ONLINE);
                assertThat(o.status()).isNotEqualTo(OrderStatus.CANCELLED);
                assertThat(o.expectedDeliveryDays()).isBetween(1, 5);
            }
            if (o.status() == OrderStatus.CANCELLED) {
                assertThat(o.staffNote()).isNotBlank(); // đơn hủy luôn có lý do nội bộ
            }
        }
        // đơn online không hủy luôn có ngày giao dự kiến
        assertThat(plan.orders().stream().filter(o -> o.channel() == Channel.ONLINE
                && o.status() != OrderStatus.CANCELLED)).allSatisfy(o ->
                assertThat(o.expectedDeliveryDays()).isNotNull());
    }

    @Test
    void tatDinhCungSeedCungKeHoach() {
        assertThat(DemoPlan.generate(TODAY, DemoPlan.DEFAULT_SEED)).isEqualTo(plan);
        assertThat(DemoPlan.generate(TODAY.plusDays(3), DemoPlan.DEFAULT_SEED).stockItems()).isEqualTo(plan.stockItems());
    }
}
