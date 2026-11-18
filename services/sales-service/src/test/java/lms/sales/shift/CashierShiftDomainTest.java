package lms.sales.shift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftSettings;
import lms.sales.shift.domain.ShiftStatus;
import lms.sales.shift.domain.VarianceStatus;
import org.junit.jupiter.api.Test;

/** Quy tắc ca thu ngân ở domain thuần (ADR-0030 D3): tiền dự kiến, chênh lệch, ngưỡng, duyệt, mở lại. */
class CashierShiftDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-19T01:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-19T10:00:00Z");
    private final UUID tenant = UUID.randomUUID();
    private final UUID cashier = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();

    private CashierShift open(long openingFloat) {
        return CashierShift.open(tenant, "CA-000001", cashier, "Quầy 1", openingFloat, UUID.randomUUID(), T0);
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    @Test
    void moCaTienDauCaKhongAmVaNguoiThuNganBatBuoc() {
        CashierShift s = open(500_000);
        assertEquals(ShiftStatus.OPEN, s.status());
        assertEquals(VarianceStatus.NONE, s.varianceStatus());
        assertEquals(500_000, s.openingFloat());
        assertEquals("Quầy 1", s.registerLabel());
        assertTrue(s.isOwnedBy(cashier));
        assertFalse(s.isOwnedBy(manager));
        assertFalse(s.isOwnedBy(null));
        assertEquals(0, open(0).openingFloat(), "tiền đầu ca 0 hợp lệ");
        assertEquals(ErrorCode.INVALID_PRICE, codeOf(() -> open(-1)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> CashierShift.open(tenant, "CA-1", null, null, 0,
                UUID.randomUUID(), T0)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> CashierShift.open(tenant, "CA-1", cashier, null, 0,
                null, T0)));
    }

    @Test
    void tienMatDuKienBangDauCaCongThuTruHoan() {
        assertEquals(1_250_000, CashierShift.expectedCash(500_000, 900_000, 150_000));
        assertEquals(500_000, CashierShift.expectedCash(500_000, 0, 0), "ca không giao dịch: bằng tiền đầu ca");
        assertEquals(350_000, CashierShift.expectedCash(500_000, 100_000, 250_000),
                "hoàn nhiều hơn thu (hoàn đơn của ca trước) làm dự kiến thấp hơn tiền đầu ca");
    }

    @Test
    void chenhLechThuaLaDuongThieuLaAm() {
        assertEquals(20_000, CashierShift.variance(1_270_000, 1_250_000), "thừa");
        assertEquals(-30_000, CashierShift.variance(1_220_000, 1_250_000), "thiếu");
        assertEquals(0, CashierShift.variance(1_250_000, 1_250_000));
    }

    @Test
    void nguongMacDinhKhongMoiChenhLechKhacKhongDeuCanLyDo() {
        assertFalse(CashierShift.exceedsThreshold(0, 0));
        assertTrue(CashierShift.exceedsThreshold(1, 0));
        assertTrue(CashierShift.exceedsThreshold(-1, 0));
        assertFalse(CashierShift.exceedsThreshold(5_000, 5_000), "bằng ngưỡng thì chưa vượt");
        assertTrue(CashierShift.exceedsThreshold(5_001, 5_000));
        assertTrue(CashierShift.exceedsThreshold(-5_001, 5_000), "so sánh trị tuyệt đối");
        assertEquals(0, ShiftSettings.defaults(tenant).varianceThreshold());
        assertFalse(ShiftSettings.defaults(tenant).requireOpenShiftForPos(), "mặc định không chặn bán");
    }

    @Test
    void dongCaKhopTienKhongCanLyDoVaKhongCanDuyet() {
        CashierShift s = open(500_000);
        s.close(1_250_000, 1_250_000, null, 0, cashier, T1);
        assertEquals(ShiftStatus.CLOSED, s.status());
        assertEquals(0, s.variance());
        assertEquals(VarianceStatus.NONE, s.varianceStatus());
        assertEquals(T1, s.closedAt());
        assertEquals(cashier, s.closedBy());
        assertEquals(1_250_000, s.expectedCash());
        assertEquals(1_250_000, s.countedCash());
    }

    @Test
    void dongCaThieuHoacThuaVuotNguongBatBuocLyDoVaChoDuyet() {
        CashierShift thieu = open(500_000);
        assertEquals(ErrorCode.SHIFT_VARIANCE_REASON_REQUIRED,
                codeOf(() -> thieu.close(1_220_000, 1_250_000, "  ", 0, cashier, T1)));
        assertTrue(thieu.isOpen(), "lỗi thiếu lý do không đổi trạng thái");
        thieu.close(1_220_000, 1_250_000, " Trả nhầm tiền thối ", 0, cashier, T1);
        assertEquals(-30_000, thieu.variance());
        assertEquals("Trả nhầm tiền thối", thieu.varianceReason());
        assertEquals(VarianceStatus.PENDING_APPROVAL, thieu.varianceStatus());

        CashierShift thua = open(500_000);
        thua.close(1_270_000, 1_250_000, "Khách để lại tiền boa", 0, cashier, T1);
        assertEquals(20_000, thua.variance());
        assertEquals(VarianceStatus.PENDING_APPROVAL, thua.varianceStatus());
    }

    @Test
    void chenhLechTrongNguongTenantKhongCanLyDo() {
        CashierShift s = open(0);
        s.close(98_000, 100_000, null, 5_000, cashier, T1);
        assertEquals(-2_000, s.variance());
        assertEquals(VarianceStatus.NONE, s.varianceStatus());
        assertNull(s.varianceReason());

        CashierShift v = open(0);
        assertEquals(ErrorCode.SHIFT_VARIANCE_REASON_REQUIRED,
                codeOf(() -> v.close(94_999, 100_000, null, 5_000, cashier, T1)), "vượt ngưỡng 5.000");
    }

    @Test
    void khongDongCaDaDongHoacTienAmHoacLyDoQuaDai() {
        CashierShift s = open(0);
        assertEquals(ErrorCode.INVALID_PRICE, codeOf(() -> s.close(-1, 0, null, 0, cashier, T1)));
        assertEquals(ErrorCode.INVALID_REQUEST,
                codeOf(() -> s.close(0, 0, "x".repeat(501), 0, cashier, T1)));
        s.close(0, 0, null, 0, cashier, T1);
        assertEquals(ErrorCode.SHIFT_STATE_CONFLICT, codeOf(() -> s.close(0, 0, null, 0, cashier, T1)),
                "ca đã đóng bị khóa");
    }

    @Test
    void duyetChenhLechChiKhiDangChoDuyet() {
        CashierShift s = open(0);
        s.close(0, 0, null, 0, cashier, T1);
        assertEquals(ErrorCode.SHIFT_STATE_CONFLICT, codeOf(() -> s.approveVariance("ok", manager, T1)),
                "không có chênh lệch thì không có gì để duyệt");

        CashierShift v = open(0);
        v.close(10, 0, "thừa 10đ", 0, cashier, T1);
        v.approveVariance("  Đã kiểm camera ", manager, T1.plusSeconds(60));
        assertEquals(VarianceStatus.APPROVED, v.varianceStatus());
        assertEquals(manager, v.approvedBy());
        assertEquals("Đã kiểm camera", v.approvalNote());
        assertEquals(T1.plusSeconds(60), v.approvedAt());
        assertEquals(ErrorCode.SHIFT_STATE_CONFLICT, codeOf(() -> v.approveVariance(null, manager, T1)),
                "duyệt hai lần");

        CashierShift openShift = open(0);
        assertEquals(ErrorCode.SHIFT_STATE_CONFLICT, codeOf(() -> openShift.approveVariance(null, manager, T1)),
                "ca đang mở chưa duyệt được");
    }

    @Test
    void moLaiCanLyDoXoaSoLieuDongVaTangDem() {
        CashierShift s = open(100);
        s.close(90, 100, "thiếu 10", 0, cashier, T1);
        assertEquals(ErrorCode.REOPEN_REASON_REQUIRED, codeOf(() -> s.reopen(" ")));
        assertEquals(ErrorCode.REOPEN_REASON_REQUIRED, codeOf(() -> s.reopen(null)));
        assertEquals(ShiftStatus.CLOSED, s.status(), "lỗi thiếu lý do không mở lại");

        s.reopen("Nhập nhầm số đếm");
        assertEquals(ShiftStatus.OPEN, s.status());
        assertEquals(1, s.reopenCount());
        assertNull(s.closedAt());
        assertNull(s.countedCash());
        assertNull(s.expectedCash());
        assertNull(s.variance());
        assertEquals(VarianceStatus.NONE, s.varianceStatus());

        s.close(100, 100, null, 0, cashier, T1.plusSeconds(600));
        assertEquals(VarianceStatus.NONE, s.varianceStatus(), "đóng lại sinh số liệu mới");
        s.reopen("lần 2");
        assertEquals(2, s.reopenCount());
    }

    @Test
    void khongMoLaiCaDangMo() {
        CashierShift s = open(0);
        assertEquals(ErrorCode.SHIFT_STATE_CONFLICT, codeOf(() -> s.reopen("lý do")));
    }

    @Test
    void cauHinhCaKiemNguongKhongAm() {
        ShiftSettings d = ShiftSettings.defaults(tenant);
        assertEquals(ErrorCode.INVALID_SETTINGS, codeOf(() -> d.update(false, -1, T0, cashier)));
        assertEquals(ErrorCode.INVALID_SETTINGS,
                codeOf(() -> d.update(false, ShiftSettings.MAX_THRESHOLD + 1, T0, cashier)));
        ShiftSettings u = d.update(true, 10_000, T0, cashier);
        assertTrue(u.requireOpenShiftForPos());
        assertEquals(10_000, u.varianceThreshold());
    }
}
