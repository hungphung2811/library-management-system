package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lms.sales.ordering.application.CommitPosOrderUseCase;
import lms.sales.ordering.application.CommitPosPendingOrderUseCase;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.application.PaymentIntentResponse;
import lms.sales.ordering.application.PlacePosOrderCommand;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.PosOrderResult;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.sales.ordering.application.SyncGatewayPaymentUseCase;
import lms.sales.ordering.domain.OrderStatus;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.PaymentStatus;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.support.Harness;
import org.junit.jupiter.api.Test;

/** Luồng POS SEPAY: chọn cổng theo phương thức, đồng bộ khi thăm dò (ADR-0028) trên bộ giả trong bộ nhớ. */
class SepaySyncUseCaseTest {

    private final Harness h = new Harness();
    private final AtomicReference<PaymentGatewayPort.GatewayPayment> found = new AtomicReference<>();
    private final AtomicReference<Object[]> lastQuery = new AtomicReference<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    private final PaymentGatewayPort sepay = new PaymentGatewayPort() {
        @Override
        public String provider() {
            return PaymentIntent.PROVIDER_SEPAY;
        }

        @Override
        public QrCharge createQr(ChargeRequest request) {
            return new QrCharge("QR-" + request.reference());
        }

        @Override
        public Optional<GatewayPayment> queryPayment(String reference, long amount, Instant notBefore) {
            lastQuery.set(new Object[] {reference, amount, notBefore});
            if (failure.get() != null) {
                throw failure.get();
            }
            return Optional.ofNullable(found.get());
        }
    };

    private final lms.sales.support.QrFixture fx = new lms.sales.support.QrFixture(h);
    private final CommitPosPendingOrderUseCase commit = new CommitPosPendingOrderUseCase(h.orderRepo, h.intents,
            List.of(fx.gateway, sepay), h.stockService, h.getSettings, h.numbers, h.auditPort, h.tenantDirectory,
            h.returnRepo, h.clock, Duration.ofMinutes(10), Duration.ofMinutes(10));
    private final PlacePosOrderUseCase placePos = new PlacePosOrderUseCase(h.orderRepo, h.catalog,
            new CommitPosOrderUseCase(h.orderRepo, h.stockService, h.getSettings, h.numbers, h.auditPort, h.clock,
                    h.tenantDirectory, h.returnRepo), commit);
    private final SyncGatewayPaymentUseCase sync = new SyncGatewayPaymentUseCase(List.of(fx.gateway, sepay),
            fx.apply);

    private PosOrderResult place(UUID title, PaymentMethod method) {
        return h.tx(() -> placePos.createWithPayment(new PlacePosOrderCommand(h.tenant, h.user,
                List.of(new PlacePosOrderCommand.Line(title, 1, null)), method, null, null, null, null,
                UUID.randomUUID())));
    }

    @Test
    void phuongThucSepayChonCongSepayKhongNhamSangSimulated() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PaymentIntentResponse i = place(a, PaymentMethod.SEPAY).paymentIntent();
        assertEquals("SEPAY", i.provider());
        assertEquals("QR-" + i.reference(), i.qrPayload());
        assertEquals(PaymentIntentStatus.PENDING, i.status());
    }

    @Test
    void thamDoGhiNhanPaidQuaLuongWebhookKemSoTienVaMocThoiGian() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PosOrderResult r = place(a, PaymentMethod.SEPAY);
        PaymentIntentResponse i = r.paymentIntent();
        found.set(new PaymentGatewayPort.GatewayPayment(true, "SEPAY-tx1", i.amount()));

        PaymentIntentResponse after = h.tx(() -> sync.refresh(h.tenant, i));

        assertEquals(PaymentIntentStatus.PAID, after.status());
        Object[] q = lastQuery.get();
        assertEquals(i.reference(), q[0]);
        assertEquals(i.amount(), q[1]);
        assertEquals(i.createdAt(), q[2]);
        assertEquals(OrderStatus.COMPLETED, h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow().status());
        assertEquals(PaymentStatus.PAID, h.orderRepo.findById(h.tenant, r.order().id()).orElseThrow().paymentStatus());
    }

    @Test
    void chuaCoGiaoDichThiGiuPendingVaLoiCongKhongLamHongThamDo() {
        UUID a = h.book("Sách A", 100_000, null, 10);
        PaymentIntentResponse i = place(a, PaymentMethod.SEPAY).paymentIntent();

        assertEquals(PaymentIntentStatus.PENDING, sync.refresh(h.tenant, i).status());

        failure.set(new SalesException(ErrorCode.PAYMENT_GATEWAY_ERROR, "429"));
        assertEquals(PaymentIntentStatus.PENDING, sync.refresh(h.tenant, i).status());
    }
}
