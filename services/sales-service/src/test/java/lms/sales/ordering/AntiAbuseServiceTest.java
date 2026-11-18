package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.AntiAbuseService;
import lms.sales.ordering.application.AntiAbuseService.RiskLevel;
import lms.sales.ordering.application.AntiAbuseService.Signals;
import lms.sales.ordering.application.PlaceOnlineOrderCommand;
import lms.sales.ordering.domain.Fulfilment;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Recipient;
import lms.sales.settings.application.OrderAbuseLimits;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** Chấm rủi ro (AntiAbuseService) và hành động theo mức khi đặt đơn online. */
class AntiAbuseServiceTest {

    private final AntiAbuseService svc = new AntiAbuseService(new AntiAbuseService.Thresholds(5, 30, 50, 80));
    private final OrderAbuseLimits limits = new OrderAbuseLimits(15, 6, 20, 50, 100, 5, 300);

    @Test
    void khachBinhThuongLaLow() {
        var a = svc.assess(new Signals(10, 1, 1, 0, 2), limits);
        assertEquals(RiskLevel.LOW, a.level());
        assertEquals(6, a.effectiveMaxPending());
        assertFalse(a.blocked());
    }

    @Test
    void itDonThiKhongXetTyLeHuy() {
        assertEquals(RiskLevel.LOW, svc.assess(new Signals(4, 4, 0, 0, 1), limits).level());
    }

    @Test
    void tyLeHuyTangDanLenMediumHighCritical() {
        assertEquals(RiskLevel.MEDIUM, svc.assess(new Signals(10, 3, 0, 0, 1), limits).level());
        var high = svc.assess(new Signals(10, 5, 0, 0, 1), limits);
        assertEquals(RiskLevel.HIGH, high.level());
        assertEquals(3, high.effectiveMaxPending()); // siết còn một nửa
        assertTrue(svc.assess(new Signals(10, 8, 0, 0, 1), limits).blocked());
    }

    @Test
    void soLuongBatThuongLaMediumVaDatDonDonLienTiepLaHigh() {
        assertEquals(RiskLevel.MEDIUM, svc.assess(new Signals(0, 0, 0, 0, 60), limits).level());
        assertEquals(RiskLevel.HIGH, svc.assess(new Signals(15, 0, 15, 0, 1), limits).level());
    }

    // ---- Tích hợp vào luồng đặt đơn ----

    private final Harness h = new Harness();
    private final UUID customer = UUID.randomUUID();

    private void place(UUID title) {
        h.tx(() -> h.placeOnline.place(new PlaceOnlineOrderCommand(h.tenant, customer,
                List.of(new PlaceOnlineOrderCommand.Line(title, 1)), Fulfilment.PICKUP, PaymentMethod.CASH,
                new Recipient("An", "0912345678", null), UUID.randomUUID())));
    }

    @Test
    void khachHuyLienTucBiChanCriticalRoiTuMoKhoaSau30Ngay() {
        h.setAbusePolicy(15, 50, 50, 50, 100);
        UUID a = h.book("Sách A", 100_000, null, 100);
        for (int i = 0; i < 5; i++) {
            place(a);
        }
        for (var o : h.orderRepo.search(h.tenant, new lms.sales.ordering.application.SalesOrderRepository.Criteria(
                null, null, null, null, null, null, customer), 0, 50).items()) {
            h.tx(() -> h.shopOrders.cancelMyOrder(h.tenant, customer, o.id(), "Đổi ý"));
        }

        var e = assertThrows(SalesException.class, () -> place(a));
        assertEquals(ErrorCode.ORDER_LIMIT_EXCEEDED, e.code());

        h.clock.advance(Duration.ofDays(31));
        place(a);
    }
}
