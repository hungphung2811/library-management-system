package lms.sales.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.common.csv.CsvWriter;
import lms.sales.reporting.application.OrdersCsvReportUseCase;
import lms.sales.reporting.application.ReportPlan;
import lms.sales.reporting.application.SalesStatsQueryPort.SoldTitle;
import lms.sales.reporting.application.SalesStatsUseCases;
import lms.sales.reporting.application.StockCsvReportUseCase;
import lms.sales.reporting.domain.ReportRange;
import lms.sales.reporting.domain.RevenueTrend;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.domain.StockItem;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** Use case thống kê và báo cáo CSV bán hàng (ADR-0018): doanh thu theo dòng tiền, múi giờ VN, tồn thấp, CSV. */
class ReportingUseCaseTest {

    private static final LocalDate D = LocalDate.parse("2026-11-16");
    private static final Clock NOON_VN = Clock.fixed(Instant.parse("2026-11-17T05:00:00Z"), ZoneOffset.UTC);

    private final Harness h = new Harness();
    private final ReportingFakes.StatsQuery query = new ReportingFakes.StatsQuery();

    private SalesStatsUseCases stats(Clock clock) {
        return new SalesStatsUseCases(query, h.stockPort, h.getSettings, h.catalog, clock);
    }

    // ===== doanh thu theo dòng tiền =====

    @Test
    void donDaThanhToanRoiHuyVanVaoGrossTheoNgayThanhToanVaKhoanHoanTruONgayHoan() {
        // đơn thanh toán 10:00 VN ngày D (03:00Z), hủy hôm sau và hoàn tiền 11:00 VN ngày D+1 (04:00Z)
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-16T03:00:00Z"), 100_000));
        query.refunded.add(new ReportingFakes.Refunded(Instant.parse("2026-11-17T04:00:00Z"), 100_000));

        RevenueTrend t = stats(NOON_VN).revenueTrend(h.tenant, D, D.plusDays(1));

        assertThat(t.points()).hasSize(2);
        assertThat(t.points().get(0)).isEqualTo(new RevenueTrend.Point(D, 100_000, 0, 100_000, 1));
        assertThat(t.points().get(1)).isEqualTo(new RevenueTrend.Point(D.plusDays(1), 0, 100_000, -100_000, 0));
        assertThat(t.totals()).isEqualTo(new RevenueTrend.Totals(100_000, 100_000, 0, 1));
        assertThat(t.totals().net()).isEqualTo(t.totals().gross() - t.totals().refunds());
    }

    @Test
    void netBangTongNetCuaCacNgayVaNgayKhongCoDuLieuLaKhong() {
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-14T03:00:00Z"), 50_000));
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-16T03:00:00Z"), 70_000));
        query.refunded.add(new ReportingFakes.Refunded(Instant.parse("2026-11-16T05:00:00Z"), 20_000));

        RevenueTrend t = stats(NOON_VN).revenueTrend(h.tenant, D.minusDays(3), D);

        assertThat(t.points()).extracting(RevenueTrend.Point::date)
                .containsExactly(D.minusDays(3), D.minusDays(2), D.minusDays(1), D);
        assertThat(t.points().get(0).gross()).isZero(); // 13/11 không có dữ liệu
        assertThat(t.points().stream().mapToLong(RevenueTrend.Point::net).sum()).isEqualTo(t.totals().net());
        assertThat(t.totals()).isEqualTo(new RevenueTrend.Totals(120_000, 20_000, 100_000, 2));
    }

    @Test
    void bienMuiGio2330ThuocNgayDVa0010ThuocNgayDCong1() {
        // 23:30 VN ngày D = 16:30Z ngày D; 00:10 VN ngày D+1 = 17:10Z ngày D (khác ngày theo UTC)
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-16T16:30:00Z"), 10_000));
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-16T17:10:00Z"), 20_000));

        RevenueTrend t = stats(NOON_VN).revenueTrend(h.tenant, D, D.plusDays(1));

        assertThat(t.points().get(0)).isEqualTo(new RevenueTrend.Point(D, 10_000, 0, 10_000, 1));
        assertThat(t.points().get(1)).isEqualTo(new RevenueTrend.Point(D.plusDays(1), 20_000, 0, 20_000, 1));
    }

    @Test
    void homNayTheoGioVnTinhTuDongHo() {
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-16T16:30:00Z"), 10_000));
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-16T17:10:00Z"), 20_000));

        // 23:45 VN ngày 16/11 (16:45Z): hôm nay là 16/11
        var before = stats(Clock.fixed(Instant.parse("2026-11-16T16:45:00Z"), ZoneOffset.UTC)).summary(h.tenant, null);
        assertThat(before.asOf()).isEqualTo(D);
        assertThat(before.ordersToday()).isEqualTo(1);
        assertThat(before.revenueToday()).isEqualTo(10_000);

        // 00:15 VN ngày 17/11 (17:15Z): hôm nay là 17/11
        var after = stats(Clock.fixed(Instant.parse("2026-11-16T17:15:00Z"), ZoneOffset.UTC)).summary(h.tenant, null);
        assertThat(after.asOf()).isEqualTo(D.plusDays(1));
        assertThat(after.ordersToday()).isEqualTo(1);
        assertThat(after.revenueToday()).isEqualTo(20_000);
    }

    @Test
    void summaryNhanAsOfTuBff() {
        query.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-14T03:00:00Z"), 5_000));
        var s = stats(NOON_VN).summary(h.tenant, LocalDate.parse("2026-11-14"));
        assertThat(s.asOf()).isEqualTo(LocalDate.parse("2026-11-14"));
        assertThat(s.grossToday()).isEqualTo(5_000);
        assertThat(s.refundsToday()).isZero();
    }

    @Test
    void khoangNgayKhongHopLeBiTuChoi() {
        var uc = stats(NOON_VN);
        assertThatThrownBy(() -> uc.revenueTrend(h.tenant, D, D.minusDays(1)))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_RANGE));
        assertThatThrownBy(() -> uc.revenueTrend(h.tenant, D.minusDays(366), D))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RANGE_TOO_LARGE));
        assertThat(uc.revenueTrend(h.tenant, D.minusDays(365), D).points()).hasSize(366);
    }

    @Test
    void khongTruyenKhoangThiMacDinh30NgayToiHomNayGioVn() {
        RevenueTrend t = stats(NOON_VN).revenueTrend(h.tenant, null, null);
        assertThat(t.range().to()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(t.range().from()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(t.points()).hasSize(30);
    }

    @Test
    void topBanKiemLimitVaChuyenKhoangXuongCong() {
        UUID a = UUID.randomUUID();
        query.sold.add(new SoldTitle(a, "A", 5, 500));
        var uc = stats(NOON_VN);
        assertThat(uc.topSold(h.tenant, null, null, null)).hasSize(1);
        assertThat(query.lastTopLimit).isEqualTo(10);
        assertThatThrownBy(() -> uc.topSold(h.tenant, null, null, 0)).isInstanceOf(SalesException.class);
        assertThatThrownBy(() -> uc.topSold(h.tenant, null, null, 51)).isInstanceOf(SalesException.class);
        assertThat(uc.topSold(h.tenant, D, D, 50)).hasSize(1);
        assertThat(query.lastTopRange).isEqualTo(new ReportRange(D, D));
    }

    // ===== tồn thấp =====

    private UUID stockItem(String name, Integer threshold, long onHand) {
        UUID id = UUID.randomUUID();
        h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, name, "TG", "ISBN-" + name,
                BigDecimal.valueOf(100_000)));
        h.registerItem.execute(h.tenant, h.user, id, null, threshold);
        if (onHand > 0) {
            h.adjust.execute(h.tenant, h.user, id, onHand, "ton dau");
        }
        return id;
    }

    @Test
    void sapHetTonKhopLowStockOnlyKeCaNguongRiengVaTonBangKhong() {
        stockItem("thap-nguong-chung", null, 3);   // available 3 <= 5 (ngưỡng chung mặc định)
        stockItem("du", null, 10);                 // 10 > 5
        stockItem("thap-nguong-rieng", 10, 10);    // 10 <= 10
        stockItem("het", null, 0);                 // 0 <= 5
        stockItem("nguong-rieng-thap", 1, 3);      // 3 > 1 dù <= 5

        var s = stats(NOON_VN).summary(h.tenant, null);

        int tenantThreshold = h.getSettings.execute(h.tenant).lowStockThreshold();
        long expected = h.stockPort.list(h.tenant, true, tenantThreshold, 0, 100).total();
        assertThat(s.lowStockCount()).isEqualTo(expected).isEqualTo(3);
        assertThat(s.lowStockItems()).hasSize(3);
        assertThat(s.lowStockItems()).extracting(i -> i.titleName())
                .containsExactlyInAnyOrder("thap-nguong-chung", "thap-nguong-rieng", "het");
        assertThat(s.lowStockItems()).anySatisfy(i -> {
            assertThat(i.titleName()).isEqualTo("thap-nguong-rieng");
            assertThat(i.threshold()).isEqualTo(10);
        });
    }

    @Test
    void sapHetTonToiDa10MatHangVaCatalogLoiThiTenTrong() {
        for (int i = 0; i < 12; i++) {
            stockItem("x" + i, null, 1);
        }
        CatalogLookupPort down = ids -> {
            throw new SalesException(ErrorCode.CATALOG_UNAVAILABLE, "down");
        };
        var s = new SalesStatsUseCases(query, h.stockPort, h.getSettings, down, NOON_VN).summary(h.tenant, null);
        assertThat(s.lowStockCount()).isEqualTo(12);
        assertThat(s.lowStockItems()).hasSize(10).allSatisfy(i -> assertThat(i.titleName()).isNull());
    }

    @Test
    void soDonOnlineChoXacNhanLayTuCong() {
        query.pendingOnline = 4;
        assertThat(stats(NOON_VN).summary(h.tenant, null).pendingOnlineOrders()).isEqualTo(4);
    }

    // ===== CSV tồn kho =====

    /** Cổng tồn kho phân trang thật trên dữ liệu của Harness (Harness.list không cắt trang). */
    private StockPort pagedStock() {
        return new StockPort() {
            public java.util.Optional<StockItem> find(UUID t, UUID id) { return h.stockPort.find(t, id); }
            public java.util.Optional<StockItem> findForUpdate(UUID t, UUID id) { return h.stockPort.find(t, id); }
            public Map<UUID, StockItem> findByTitleIds(UUID t, Collection<UUID> ids) { return h.stockPort.findByTitleIds(t, ids); }
            public StockItem insert(StockItem i) { return h.stockPort.insert(i); }
            public StockItem saveAttributes(StockItem i) { return h.stockPort.saveAttributes(i); }
            public boolean tryApply(UUID t, UUID id, long a, long b) { return h.stockPort.tryApply(t, id, a, b); }
            public void append(lms.sales.stock.domain.StockMovement m) { h.stockPort.append(m); }
            public PageResult<StockItem> list(UUID t, boolean low, int thr, int page, int size) {
                return h.stockPort.list(t, low, thr, page, size);
            }
            @Override
            public PageResult<StockItem> listForReport(UUID t, boolean low, int thr, int page, int size) {
                var all = h.stockPort.list(t, low, thr, 0, Integer.MAX_VALUE).items().stream()
                        .sorted(java.util.Comparator.comparingLong(StockItem::available)
                                .thenComparing(StockItem::titleId)).toList();
                int from = Math.min(all.size(), page * size);
                return new PageResult<>(all.subList(from, Math.min(all.size(), from + size)), all.size(), page, size);
            }
            public PageResult<StockItem> listActive(UUID t, int page, int size) { return h.stockPort.listActive(t, page, size); }
            public PageResult<lms.sales.stock.domain.StockMovement> ledger(UUID t, UUID id, int page, int size) { return h.stockPort.ledger(t, id, page, size); }
            public LedgerTotals ledgerTotals(UUID t, UUID id) { return h.stockPort.ledgerTotals(t, id); }
        };
    }

    private static String csvText(ByteArrayOutputStream out) {
        byte[] b = out.toByteArray();
        assertThat(b[0]).isEqualTo((byte) 0xEF);
        assertThat(b[1]).isEqualTo((byte) 0xBB);
        assertThat(b[2]).isEqualTo((byte) 0xBF);
        return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    void csvTonKhoCoBomTieuDeDungVaChongCsvInjection() {
        stockItem("=cmd|' /C calc'!A0", null, 2);
        stockItem("Sach, co \"dau\" phay", null, 50);
        var uc = new StockCsvReportUseCase(pagedStock(), h.catalog, h.getSettings, NOON_VN, 10_000);

        ReportPlan plan = uc.prepare(h.tenant, false);
        var out = new ByteArrayOutputStream();
        int rows = uc.write(h.tenant, false, plan, CsvWriter.toStream(out));

        assertThat(plan.truncated()).isFalse();
        assertThat(plan.filename()).isEqualTo("stock-20261117.csv");
        assertThat(rows).isEqualTo(2);
        String[] lines = csvText(out).split("\r\n");
        assertThat(lines[0]).isEqualTo("titleId,titleName,isbn,onHand,reserved,available,threshold,lowStock,sellingPrice");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains("'=cmd|' /C calc'!A0").contains(",2,0,2,5,true,100000");
        assertThat(lines[2]).contains("\"Sach, co \"\"dau\"\" phay\"").contains(",50,0,50,5,false,100000");
    }

    @Test
    void csvTonKhoLowStockOnlyChiCoDongTonThapSapTheoAvailableTang() {
        stockItem("a", null, 4);
        stockItem("b", null, 40);
        stockItem("c", null, 1);
        var uc = new StockCsvReportUseCase(pagedStock(), h.catalog, h.getSettings, NOON_VN, 10_000);
        ReportPlan plan = uc.prepare(h.tenant, true);
        var out = new ByteArrayOutputStream();
        uc.write(h.tenant, true, plan, CsvWriter.toStream(out));
        String[] lines = csvText(out).split("\r\n");
        assertThat(lines).hasSize(3); // tiêu đề + 2 dòng tồn thấp
        assertThat(lines[1]).contains(",1,0,1,5,true,");
        assertThat(lines[2]).contains(",4,0,4,5,true,");
    }

    @Test
    void csvTonKhoVuotTranBiCatVaPhanTrangQuaNhieuLo() {
        for (int i = 0; i < 230; i++) {
            stockItem("s" + i, null, 100);
        }
        var uc = new StockCsvReportUseCase(pagedStock(), h.catalog, h.getSettings, NOON_VN, 205);
        ReportPlan plan = uc.prepare(h.tenant, false);
        assertThat(plan.truncated()).isTrue();
        assertThat(plan.totalRows()).isEqualTo(230);
        assertThat(plan.rows()).isEqualTo(205);
        var out = new ByteArrayOutputStream();
        assertThat(uc.write(h.tenant, false, plan, CsvWriter.toStream(out))).isEqualTo(205);
        assertThat(csvText(out).split("\r\n")).hasSize(206);
    }

    @Test
    void csvTonKhoCatalogLoiVanXuatDuDongVoiTenTrong() {
        stockItem("co-ten", null, 2);
        CatalogLookupPort down = ids -> {
            throw new SalesException(ErrorCode.CATALOG_UNAVAILABLE, "down");
        };
        var uc = new StockCsvReportUseCase(pagedStock(), down, h.getSettings, NOON_VN, 10_000);
        ReportPlan plan = uc.prepare(h.tenant, false);
        var out = new ByteArrayOutputStream();
        assertThat(uc.write(h.tenant, false, plan, CsvWriter.toStream(out))).isEqualTo(1);
        String[] lines = csvText(out).split("\r\n");
        assertThat(lines[1]).contains(",,,2,0,2,5,true,"); // titleName và isbn trống, không có giá bìa để suy ra
        assertThat(lines[1]).endsWith(",true,");
    }

    // ===== CSV đơn hàng =====

    @Test
    void csvDonHangKhongCoCotThongTinKhachVaGhiThoiDiemIsoGioVn() {
        var report = new ReportingFakes.OrderReport();
        report.rows.add(ReportingFakes.OrderReport.row(1));
        var uc = new OrdersCsvReportUseCase(report, NOON_VN, 10_000);

        var prepared = uc.prepare(h.tenant, null, null, "pos", null);
        var out = new ByteArrayOutputStream();
        uc.write(h.tenant, prepared, CsvWriter.toStream(out));

        String[] lines = csvText(out).split("\r\n");
        assertThat(lines[0]).isEqualTo("orderNo,channel,status,paymentStatus,createdAt,paidAt,itemCount,subtotal,"
                + "discount,vat,shippingFee,total");
        assertThat(lines[0].toLowerCase()).doesNotContain("name", "phone", "address", "recipient", "customer",
                "walk");
        assertThat(lines[1]).isEqualTo("DEMO-1,POS,COMPLETED,PAID,2026-11-16T17:00:00+07:00,"
                + "2026-11-16T17:00:00+07:00,2,90000,0,4500,0,94500");
        assertThat(report.lastFilter.channel()).isEqualTo("POS");
        assertThat(report.lastFilter.range().to()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(prepared.plan().filename()).isEqualTo("orders-20261117.csv");
    }

    @Test
    void csvDonHangVuotTranCatDungSoDongVaDocTheoLo() {
        var report = new ReportingFakes.OrderReport();
        for (int i = 0; i < 1300; i++) {
            report.rows.add(ReportingFakes.OrderReport.row(i));
        }
        var uc = new OrdersCsvReportUseCase(report, NOON_VN, 1100);
        var prepared = uc.prepare(h.tenant, null, null, null, null);
        assertThat(prepared.plan().truncated()).isTrue();
        var out = new ByteArrayOutputStream();
        assertThat(uc.write(h.tenant, prepared, CsvWriter.toStream(out))).isEqualTo(1100);
        String[] lines = csvText(out).split("\r\n");
        assertThat(lines).hasSize(1101);
        assertThat(lines[1100]).startsWith("DEMO-1099,");
        assertThat(report.pages).isEqualTo(3); // 500 + 500 + 100
    }

    @Test
    void csvDonHangDuoiTranKhongBiCat() {
        var report = new ReportingFakes.OrderReport();
        report.rows.add(ReportingFakes.OrderReport.row(1));
        var prepared = new OrdersCsvReportUseCase(report, NOON_VN, 10_000).prepare(h.tenant, null, null, null, null);
        assertThat(prepared.plan().truncated()).isFalse();
    }

    @Test
    void csvDonHangThamSoSaiBiTuChoiTruocKhiGhi() {
        var uc = new OrdersCsvReportUseCase(new ReportingFakes.OrderReport(), NOON_VN, 10_000);
        assertThatThrownBy(() -> uc.prepare(h.tenant, null, null, "MOBILE", null))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> uc.prepare(h.tenant, null, null, null, "WHATEVER")).isInstanceOf(SalesException.class);
        assertThatThrownBy(() -> uc.prepare(h.tenant, D, D.minusDays(1), null, null))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_RANGE));
        assertThatThrownBy(() -> uc.prepare(h.tenant, D.minusDays(400), D, null, null))
                .isInstanceOfSatisfying(SalesException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RANGE_TOO_LARGE));
    }

    @Test
    void khoangGioReportRangeTheoGioVn() {
        var r = new ReportRange(D, D);
        assertThat(r.start()).isEqualTo(Instant.parse("2026-11-15T17:00:00Z"));
        assertThat(r.endExclusive()).isEqualTo(Instant.parse("2026-11-16T17:00:00Z"));
        assertThat(r.days()).isEqualTo(1);
        assertThat(List.copyOf(r.dates())).containsExactly(D);
    }
}
