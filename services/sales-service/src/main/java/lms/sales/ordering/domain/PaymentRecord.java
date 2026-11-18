package lms.sales.ordering.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.Money;

/**
 * Bản ghi thu tiền của đơn. {@code shiftId} (ADR-0030) là ca thu ngân nhận tiền, null khi tenant không dùng ca hoặc
 * người thu không có ca mở (đơn online, đơn cũ).
 */
public record PaymentRecord(UUID id, Money amount, PaymentMethod method, String reference, UUID recordedBy,
                            Instant recordedAt, UUID shiftId) {

    /** Bản ghi chưa gắn ca. */
    public PaymentRecord(UUID id, Money amount, PaymentMethod method, String reference, UUID recordedBy,
                         Instant recordedAt) {
        this(id, amount, method, reference, recordedBy, recordedAt, null);
    }

    public PaymentRecord withShift(UUID shift) {
        return new PaymentRecord(id, amount, method, reference, recordedBy, recordedAt, shift);
    }
}
