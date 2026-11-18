package lms.circulation.policy.domain;

import java.time.Instant;
import java.util.UUID;

/** Bậc hỏng đã lưu kèm version (khóa lạc quan) và dấu vết tạo/sửa. */
public record DamageLevelSnapshot(DamageLevel level, long version, Instant createdAt, Instant updatedAt, UUID updatedBy) {
}
