package lms.identity.contract.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Nhắc hợp đồng sắp hết hạn (topic identity-events, eventType {@code TenantContractExpiringSoon}). Payload KHÔNG có
 * tiền hay ghi chú nội bộ. eventId tất định theo (hợp đồng, mốc, ngày kết thúc) để Kafka giao lại không nhân bản
 * thông báo ở support (UNIQUE tenant_id + event_id).
 */
public record TenantContractExpiringSoonEvent(UUID eventId, UUID tenantId, UUID contractId, String contractName,
                                              LocalDate endDate, int daysBefore, List<UUID> recipientUserIds,
                                              Instant occurredAt) implements DomainEvent {

    public static final String TYPE = "TenantContractExpiringSoon";

    public static TenantContractExpiringSoonEvent of(UUID tenantId, UUID contractId, String contractName,
                                                     LocalDate endDate, int daysBefore, Instant endsAt,
                                                     List<UUID> recipients, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("contract-reminder:" + contractId + ":" + daysBefore + ":" + endsAt)
                .getBytes(StandardCharsets.UTF_8));
        return new TenantContractExpiringSoonEvent(id, tenantId, contractId, contractName, endDate, daysBefore,
                List.copyOf(recipients), now);
    }

    @Override
    public String aggregateId() {
        return contractId.toString();
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
