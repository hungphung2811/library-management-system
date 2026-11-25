package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.infrastructure.client.ThrottledPaymentGateway;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

/** Bộ hãm lời gọi SePay (ADR-0029 mục 2.4) với đồng hồ giả: theo lệnh 1 lần/5 giây, toàn cục 2 lần/giây. */
class ThrottledPaymentGatewayTest {

    /** Đồng hồ chỉnh được theo mili giây. */
    private static final class TestClock extends Clock {
        private long millis = Instant.parse("2026-11-19T03:00:00Z").toEpochMilli();

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private final TestClock clock = new TestClock();
    private final List<String> calls = new ArrayList<>();
    private RuntimeException failure;

    private final PaymentGatewayPort delegate = new PaymentGatewayPort() {
        @Override
        public String provider() {
            return "SEPAY";
        }

        @Override
        public QrCharge createQr(ChargeRequest request) {
            return new QrCharge("QR-" + request.reference());
        }

        @Override
        public Optional<GatewayPayment> queryPayment(String reference, long amount, Instant notBefore) {
            calls.add(reference);
            if (failure != null) {
                throw failure;
            }
            return Optional.of(new GatewayPayment(true, "TX-" + reference, amount));
        }
    };

    private ThrottledPaymentGateway gateway(long intervalMs, int qps) {
        return new ThrottledPaymentGateway(delegate, clock, intervalMs, qps);
    }

    private static Optional<PaymentGatewayPort.GatewayPayment> poll(PaymentGatewayPort g, String ref) {
        return g.queryPayment(ref, 1000, Instant.EPOCH);
    }

    @Test
    void providerVaTaoQrDiThangQuaCong() {
        ThrottledPaymentGateway g = gateway(5000, 2);
        assertEquals("SEPAY", g.provider());
        assertEquals("QR-R1", g.createQr(new PaymentGatewayPort.ChargeRequest("R1",
                lms.sales.shared.domain.Money.of(1000), Instant.EPOCH.plusSeconds(10))).qrPayload());
        assertEquals(0, calls.size());
    }

    @Test
    void moiLenhChiHoiCongMotLanTrongKhoangHamCacLanSauTraRong() {
        ThrottledPaymentGateway g = gateway(5000, 100);
        assertTrue(poll(g, "R1").isPresent());
        clock.advance(1000);
        assertTrue(poll(g, "R1").isEmpty(), "chưa đủ 5 giây: không gọi cổng");
        clock.advance(3999);
        assertTrue(poll(g, "R1").isEmpty(), "4999ms vẫn trong khoảng hãm");
        assertEquals(1, calls.size());
        clock.advance(1);
        assertTrue(poll(g, "R1").isPresent(), "đủ 5 giây thì hỏi lại");
        assertEquals(2, calls.size());
    }

    @Test
    void lenhKhacNhauKhongHamNhauTheoLenh() {
        ThrottledPaymentGateway g = gateway(5000, 100);
        assertTrue(poll(g, "R1").isPresent());
        assertTrue(poll(g, "R2").isPresent());
        assertEquals(List.of("R1", "R2"), calls);
    }

    @Test
    void hanMucToanCucHaiLanMotGiayHetTokenThiTraRongVaHoiPhucSauKhiThoiGianTroi() {
        ThrottledPaymentGateway g = gateway(5000, 2);
        assertTrue(poll(g, "R1").isPresent());
        assertTrue(poll(g, "R2").isPresent());
        assertTrue(poll(g, "R3").isEmpty(), "token cạn trong cùng một giây");
        assertEquals(2, calls.size());

        clock.advance(500);
        assertTrue(poll(g, "R3").isPresent(), "nửa giây nạp lại một token (2/giây)");
        assertTrue(poll(g, "R4").isEmpty());
        assertEquals(3, calls.size());

        clock.advance(10_000);
        assertTrue(poll(g, "R4").isPresent());
        assertTrue(poll(g, "R5").isPresent());
        assertTrue(poll(g, "R6").isEmpty(), "token không tích lũy quá hạn mức (tối đa 2)");
    }

    @Test
    void lenhBiHamTheoLenhKhongTonTokenToanCuc() {
        ThrottledPaymentGateway g = gateway(5000, 2);
        assertTrue(poll(g, "R1").isPresent());
        assertTrue(poll(g, "R1").isEmpty());
        assertTrue(poll(g, "R1").isEmpty());
        assertTrue(poll(g, "R2").isPresent(), "các lần R1 bị hãm không được tiêu token");
    }

    @Test
    void kenhCuoiBoQuaHamTheoLenhNhungVanQuaHanMucToanCuc() {
        ThrottledPaymentGateway g = gateway(5000, 2);
        assertTrue(poll(g, "R1").isPresent());
        assertTrue(poll(g, "R1").isEmpty());
        assertTrue(g.queryPaymentFinal("R1", 1000, Instant.EPOCH).isPresent(), "kiểm lần cuối không bị hãm theo lệnh");
        assertTrue(g.queryPaymentFinal("R1", 1000, Instant.EPOCH).isEmpty(), "nhưng vẫn qua hạn mức toàn cục");
        assertEquals(2, calls.size());
    }

    @Test
    void kenhCuoiGhiNhanMocDeLanThamDoThuongSauDoVanBiHam() {
        ThrottledPaymentGateway g = gateway(5000, 100);
        assertTrue(g.queryPaymentFinal("R1", 1000, Instant.EPOCH).isPresent());
        assertTrue(poll(g, "R1").isEmpty());
    }

    @Test
    void loiCuaCongDuocNemTiepVaLanGoiVanTinhVaoHan() {
        ThrottledPaymentGateway g = gateway(5000, 100);
        failure = new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, "429");
        assertThrows(SalesException.class, () -> poll(g, "R1"));
        failure = null;
        assertTrue(poll(g, "R1").isEmpty(), "lần vừa lỗi vẫn tính là đã hỏi, tránh dồn dập khi cổng đang lỗi");
        assertEquals(1, calls.size());
    }

    @Test
    void khongCoSoTienVaMocThoiGianThiDiThangQuaCongBanTheoMaThamChieu() {
        PaymentGatewayPort inner = new PaymentGatewayPort() {
            public String provider() {
                return "X";
            }

            public QrCharge createQr(ChargeRequest r) {
                return new QrCharge("q");
            }

            public Optional<GatewayPayment> queryPayment(String reference) {
                calls.add("ref:" + reference);
                return Optional.empty();
            }
        };
        ThrottledPaymentGateway g = new ThrottledPaymentGateway(inner, clock, 5000, 2);
        g.queryPayment("R9");
        g.queryPayment("R9");
        assertEquals(List.of("ref:R9", "ref:R9"), calls);
    }
}
