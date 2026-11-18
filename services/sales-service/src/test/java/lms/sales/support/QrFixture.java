package lms.sales.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.CommitPosOrderUseCase;
import lms.sales.ordering.application.CommitPosPendingOrderUseCase;
import lms.sales.ordering.application.ExpirePaymentIntentsUseCase;
import lms.sales.ordering.application.PaymentGatewayPort;
import lms.sales.ordering.application.PaymentIntentRepository;
import lms.sales.ordering.application.PaymentIntentUseCases;
import lms.sales.ordering.application.PaymentTenantResolverPort;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.ProcessPaymentWebhookUseCase;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.infrastructure.client.SimulatedPaymentGateway;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Bộ giả cho thanh toán QR (ADR-0020): kho lệnh thanh toán trong bộ nhớ (lưu snapshot bất biến, khóa lạc quan như DB),
 * dựng use case thật trên {@link Harness} (đơn, tồn, audit giả).
 */
public final class QrFixture {

    public final Harness h;
    public final InMemoryIntents intents;
    public final PaymentGatewayPort gateway = new SimulatedPaymentGateway("970422", "0123456789", "LMS DEMO");
    public final CommitPosPendingOrderUseCase qrCommit;
    public final PlacePosOrderUseCase placePos;
    public final PaymentIntentUseCases intentUseCases;
    public final ProcessPaymentWebhookUseCase webhook;
    public final lms.sales.ordering.application.SyncGatewayPaymentUseCase gatewaySync;
    public final ExpirePaymentIntentsUseCase expire;
    public final PaymentTenantResolverPort resolver;

    public QrFixture(Harness h) {
        this.h = h;
        this.intents = h.intents;
        this.resolver = reference -> h.intents.tenantOfReference(reference);
        this.qrCommit = new CommitPosPendingOrderUseCase(h.orderRepo, intents, java.util.List.of(gateway), h.stockService, h.getSettings,
                h.numbers, h.auditPort, h.tenantDirectory, h.returnRepo, h.clock, Duration.ofMinutes(10),
                Duration.ofMinutes(10));
        this.placePos = new PlacePosOrderUseCase(h.orderRepo, h.catalog,
                new CommitPosOrderUseCase(h.orderRepo, h.stockService, h.getSettings, h.numbers, h.auditPort, h.clock,
                        h.tenantDirectory, h.returnRepo), qrCommit);
        this.intentUseCases = h.intentUseCases;
        this.webhook = new ProcessPaymentWebhookUseCase(intents, h.orderRepo, h.stockService, h.auditPort, h.clock);
        this.gatewaySync = new lms.sales.ordering.application.SyncGatewayPaymentUseCase(java.util.List.of(gateway), webhook);
        this.expire = new ExpirePaymentIntentsUseCase(intents, intentUseCases, h.clock, 100);
    }
}
