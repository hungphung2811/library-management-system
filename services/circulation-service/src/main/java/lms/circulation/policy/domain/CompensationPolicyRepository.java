package lms.circulation.policy.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu chính sách đền bù của tenant hiện tại (1 dòng/tenant). */
public interface CompensationPolicyRepository {

    /** Chính sách đã lưu; rỗng nếu chưa cấu hình (hoặc không có tenant trong ngữ cảnh). */
    Optional<CompensationPolicySnapshot> find();

    /** Upsert; ném PolicyVersionConflictException nếu bị sửa đồng thời. */
    CompensationPolicySnapshot save(CompensationPolicy policy, UUID updatedBy, Instant now);
}
