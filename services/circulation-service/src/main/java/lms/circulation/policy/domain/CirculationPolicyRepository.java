package lms.circulation.policy.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu chính sách của tenant hiện tại (1 dòng/tenant). */
public interface CirculationPolicyRepository {

    /** Chính sách đã lưu của tenant hiện tại; rỗng nếu chưa cấu hình (hoặc không có tenant trong ngữ cảnh). */
    Optional<CirculationPolicySnapshot> find();

    /** Upsert; ném PolicyVersionConflictException nếu bị sửa đồng thời. */
    CirculationPolicySnapshot save(CirculationPolicy policy, UUID updatedBy, Instant now);
}
