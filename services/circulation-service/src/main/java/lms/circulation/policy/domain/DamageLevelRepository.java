package lms.circulation.policy.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng lưu bậc hỏng của tenant hiện tại. Mọi truy vấn tự giới hạn theo tenant (Hibernate @TenantId và RLS). */
public interface DamageLevelRepository {

    /** Mọi bậc của tenant theo thứ tự hiển thị (sortOrder rồi code); rỗng nếu chưa cấu hình. Một truy vấn. */
    List<DamageLevelSnapshot> findAll();

    Optional<DamageLevelSnapshot> findByCode(String code);

    long count();

    /** Tạo bậc mới; ném CompensationRuleException DAMAGE_LEVEL_CODE_EXISTS nếu trùng mã trong tenant. */
    DamageLevelSnapshot create(DamageLevel level, UUID updatedBy, Instant now);

    /** Sửa nhãn, tỉ lệ, thứ tự, bật/tắt (không đổi mã); expectedVersion là version đã đọc. Ném PolicyVersionConflictException khi lệch. */
    DamageLevelSnapshot update(DamageLevel level, long expectedVersion, UUID updatedBy, Instant now);

    void delete(String code);
}
