package lms.sales.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.PaymentIntentRepository;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/** Kho lệnh thanh toán: lưu snapshot, đọc ra bản mới mỗi lần (như đọc DB), version tăng khi ghi. */
public final class InMemoryIntents implements PaymentIntentRepository {

    private final Map<UUID, PaymentIntent.Snapshot> rows = new HashMap<>();
    public int saves;

    @Override
    public PaymentIntent save(PaymentIntent i) {
        PaymentIntent.Snapshot current = rows.get(i.id());
        long version = current == null ? 0 : current.version() + 1;
        if (current != null && current.version() != i.version()) {
            throw new SalesException(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT, "version lệch");
        }
        if (i.providerTxnId() != null && rows.values().stream().anyMatch(r -> !r.id().equals(i.id())
                && r.tenantId().equals(i.tenantId()) && i.providerTxnId().equals(r.providerTxnId()))) {
            throw new SalesException(ErrorCode.PAYMENT_DUPLICATE_TRANSACTION, "trùng mã giao dịch");
        }
        PaymentIntent.Snapshot s = new PaymentIntent.Snapshot(i.id(), i.tenantId(), i.orderId(), i.amount(),
                i.reference(), i.status(), i.provider(), i.providerTxnId(), i.qrPayload(), i.expiresAt(),
                i.paidAt(), i.createdAt(), version, i.tenderedAmount(), i.changeDue(), i.transactionRef());
        rows.put(i.id(), s);
        saves++;
        return PaymentIntent.restore(s);
    }

    @Override
    public Optional<PaymentIntent> findById(UUID tenantId, UUID id) {
        return Optional.ofNullable(rows.get(id)).filter(s -> s.tenantId().equals(tenantId))
                .map(PaymentIntent::restore);
    }

    @Override
    public Optional<PaymentIntent> findByReference(UUID tenantId, String reference) {
        return rows.values().stream().filter(s -> s.tenantId().equals(tenantId)
                && s.reference().equals(reference)).findFirst().map(PaymentIntent::restore);
    }

    @Override
    public Optional<PaymentIntent> findByOrderId(UUID tenantId, UUID orderId) {
        return rows.values().stream().filter(s -> s.tenantId().equals(tenantId) && s.orderId().equals(orderId))
                .findFirst().map(PaymentIntent::restore);
    }

    @Override
    public List<UUID> findExpiredPendingIds(UUID tenantId, Instant now, int limit) {
        List<UUID> out = new ArrayList<>();
        rows.values().stream().filter(s -> s.tenantId().equals(tenantId)
                        && s.status().name().equals("PENDING") && !now.isBefore(s.expiresAt()))
                .sorted(java.util.Comparator.comparing(PaymentIntent.Snapshot::expiresAt)).limit(limit)
                .forEach(s -> out.add(s.id()));
        return out;
    }

    public Optional<UUID> tenantOfReference(String reference) {
        return rows.values().stream().filter(s -> s.reference().equals(reference)).findFirst()
                .map(PaymentIntent.Snapshot::tenantId);
    }

    public int size() {
        return rows.size();
    }
}
