package lms.sales.ordering.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.Money;

public record PaymentRecord(UUID id, Money amount, PaymentMethod method, String reference, UUID recordedBy,
                            Instant recordedAt) {
}
