package lms.circulation.policy.domain;

import java.time.Instant;
import java.util.UUID;

/** Chính sách đã lưu của tenant kèm version (khóa lạc quan) và dấu vết sửa. */
public record CirculationPolicySnapshot(CirculationPolicy policy, long version, Instant updatedAt, UUID updatedBy) {
}
