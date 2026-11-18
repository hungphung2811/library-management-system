package lms.circulation.policy.domain;

import java.time.Instant;
import java.util.UUID;

/** Chính sách đền bù đã lưu của tenant kèm version (khóa lạc quan) và dấu vết sửa. */
public record CompensationPolicySnapshot(CompensationPolicy policy, long version, Instant updatedAt, UUID updatedBy) {
}
