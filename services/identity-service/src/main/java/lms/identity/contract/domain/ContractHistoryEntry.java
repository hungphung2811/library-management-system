package lms.identity.contract.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Một dòng lịch sử, chỉ thêm. {@code contractId} null cho ngoại lệ ngoài hợp đồng; {@code actorId} null khi
 * {@code actorType = SYSTEM} (job). {@code data}: trước/sau, module đã tắt, tenant có bị suspend, lý do.
 */
public record ContractHistoryEntry(UUID id, UUID tenantId, UUID contractId, String type, ActorType actorType,
                                   UUID actorId, Instant occurredAt, Map<String, Object> data) {

    public enum ActorType { USER, SYSTEM }

    public static ContractHistoryEntry by(UUID actor, UUID tenantId, UUID contractId, String type, Instant now,
                                          Map<String, Object> data) {
        return new ContractHistoryEntry(UUID.randomUUID(), tenantId, contractId, type,
                actor == null ? ActorType.SYSTEM : ActorType.USER, actor, now, data);
    }
}
