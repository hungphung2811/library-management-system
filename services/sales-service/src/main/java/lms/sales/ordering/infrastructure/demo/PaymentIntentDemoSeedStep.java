package lms.sales.ordering.infrastructure.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.OrderPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import lms.sales.ordering.application.PaymentIntentRepository;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.shared.domain.Money;
import lms.sales.shared.infrastructure.demo.DemoTimes;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Seed lệnh thanh toán (payment intents) của tenant demo (ADR-0018, ADR-0021, ADR-0028): đủ bốn trạng thái PENDING,
 * PAID, EXPIRED, CANCELLED và các cổng MANUAL (thu tay tiền mặt/thẻ tại quầy, có tiền khách đưa và tiền thối), SEPAY,
 * MOMO, SIMULATED. Dựng từ kế hoạch đơn (không gọi cổng thật, không phát event):
 * <ul>
 *   <li>đơn POS đã thu trong 7 ngày gần nhất: MANUAL PAID (tiền mặt kèm tiền đưa/thối, thẻ kèm mã giao dịch), chuyển
 *       khoản thì SEPAY PAID;</li>
 *   <li>đơn online đã thu: MOMO hoặc SEPAY PAID; đơn online chờ xác nhận chưa thu: SIMULATED PENDING (hạn còn dài);
 *       đơn online hủy chưa thu: xen kẽ EXPIRED và CANCELLED.</li>
 * </ul>
 * Idempotent theo id tất định {@code DemoIds.of("payment-intent", index đơn)}: lệnh đã có thì bỏ qua. Mã tham chiếu
 * {@code LMSDEMO<index>} là duy nhất toàn hệ thống (ràng buộc DB). Lệnh không gắn ca thu ngân.
 */
@Component
@Profile("demo-seed")
public class PaymentIntentDemoSeedStep implements DemoSeedStep {

    static final int POS_WINDOW_DAYS = 7;
    private static final long TENDER_ROUNDING = 50_000L;

    private final PaymentIntentRepository intents;
    private final TransactionOperations tx;

    public PaymentIntentDemoSeedStep(PaymentIntentRepository intents, TransactionOperations tx) {
        this.intents = intents;
        this.tx = tx;
    }

    @Override
    public int order() {
        return 35;
    }

    @Override
    public String name() {
        return "sales.payment-intents";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        if (context.tenantId() == null) {
            throw new IllegalStateException("Seed bán hàng cần lms.demo-seed.tenant-id");
        }
        UUID tenantId = context.tenantId();
        DemoPlan plan = context.plan();
        int created = 0;
        int skipped = 0;
        for (OrderPlan o : plan.orders()) {
            PaymentIntent intent = build(tenantId, plan, o, context);
            if (intent == null) {
                continue;
            }
            if (intents.findById(tenantId, intent.id()).isPresent()) {
                skipped++;
                continue;
            }
            tx.executeWithoutResult(s -> intents.save(intent));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    /** Lệnh của một đơn theo quy tắc ở mô tả lớp, hoặc null nếu đơn này không có lệnh. */
    private static PaymentIntent build(UUID tenantId, DemoPlan plan, OrderPlan o, DemoSeedContext context) {
        Instant created = DemoTimes.orderCreatedAt(o, plan, context.clock());
        Instant now = context.clock().instant();
        boolean online = o.channel() == DemoPlan.Channel.ONLINE;
        UUID id = DemoIds.of("payment-intent", o.index());
        String reference = String.format("LMSDEMO%05d", o.index());
        Money amount = Money.of(o.total());
        if (o.total() <= 0) {
            return null;
        }
        Instant expiresAt = created.plus(Duration.ofMinutes(15));

        if (!online) {
            if (!o.isPaid() || o.createdDaysAgo() > POS_WINDOW_DAYS) {
                return null;
            }
            int slot = o.index() % 3; // cùng quy tắc chọn phương thức của bước đơn hàng: CASH, CARD, BANK_TRANSFER
            return switch (slot) {
                case 0 -> {
                    long tendered = ((o.total() + TENDER_ROUNDING - 1) / TENDER_ROUNDING) * TENDER_ROUNDING;
                    yield paid(id, tenantId, o, amount, reference, PaymentIntent.PROVIDER_MANUAL, "CASH-" + o.orderNo(),
                            null, expiresAt, created, created, tendered, tendered - o.total(), null);
                }
                case 1 -> paid(id, tenantId, o, amount, reference, PaymentIntent.PROVIDER_MANUAL,
                        "CARD-" + o.orderNo(), null, expiresAt, created, created, null, null, "POS-" + o.orderNo());
                default -> paid(id, tenantId, o, amount, reference, PaymentIntent.PROVIDER_SEPAY,
                        "SEPAY-" + o.orderNo(), "DEMO-QR:" + reference, expiresAt, created, created, null, null, null);
            };
        }

        Instant paidAt = DemoTimes.plusClamped(created, Duration.ofMinutes(30), context.clock());
        if (o.isPaid()) {
            String provider = o.index() % 2 == 0 ? PaymentIntent.PROVIDER_MOMO : PaymentIntent.PROVIDER_SEPAY;
            return paid(id, tenantId, o, amount, reference, provider, provider + "-" + o.orderNo(),
                    "DEMO-QR:" + reference, expiresAt.plus(Duration.ofMinutes(20)), created, paidAt, null, null, null);
        }
        if (o.status() == DemoPlan.OrderStatus.PENDING_CONFIRMATION) {
            return PaymentIntent.restore(new PaymentIntent.Snapshot(id, tenantId, o.id(), amount, reference,
                    PaymentIntentStatus.PENDING, PaymentIntent.PROVIDER_SIMULATED, null, "DEMO-QR:" + reference,
                    now.plus(Duration.ofDays(30)), null, created, 0, null, null, null, null));
        }
        if (o.status() == DemoPlan.OrderStatus.CANCELLED) {
            PaymentIntentStatus status = o.index() % 2 == 0 ? PaymentIntentStatus.EXPIRED
                    : PaymentIntentStatus.CANCELLED;
            return PaymentIntent.restore(new PaymentIntent.Snapshot(id, tenantId, o.id(), amount, reference, status,
                    PaymentIntent.PROVIDER_SEPAY, null, "DEMO-QR:" + reference, expiresAt, null, created, 0, null,
                    null, null, null));
        }
        return null;
    }

    private static PaymentIntent paid(UUID id, UUID tenantId, OrderPlan o, Money amount, String reference,
                                      String provider, String providerTxnId, String qrPayload, Instant expiresAt,
                                      Instant createdAt, Instant paidAt, Long tendered, Long change,
                                      String transactionRef) {
        return PaymentIntent.restore(new PaymentIntent.Snapshot(id, tenantId, o.id(), amount, reference,
                PaymentIntentStatus.PAID, provider, providerTxnId, qrPayload, expiresAt, paidAt, createdAt, 0,
                tendered, change, transactionRef, null));
    }
}
