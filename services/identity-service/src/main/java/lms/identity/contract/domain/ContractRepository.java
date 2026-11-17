package lms.identity.contract.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bảng toàn cục tenant_contracts (identity_db, không RLS): mọi truy vấn theo tenant phải lọc tường minh. */
public interface ContractRepository {

    /** Ghi và flush (để ràng buộc DB phát lỗi ngay). Xung đột version → CONTRACT_VERSION_CONFLICT. */
    Contract save(Contract contract);

    Optional<Contract> findById(UUID id);

    /** Mọi hợp đồng của tenant, mới nhất (starts_at) trước. */
    List<Contract> findByTenant(UUID tenantId);

    /** Hợp đồng ACTIVE của tenant. */
    List<Contract> findActiveByTenant(UUID tenantId);

    /** Hợp đồng EXPIRED/CANCELLED của tenant còn dấu module giữ lại. */
    List<Contract> findWithRetainedModule(UUID tenantId);

    /** ACTIVE, chưa áp dụng module, starts_at &lt;= now (không khóa, chỉ để chọn ứng viên). */
    List<Contract> findDueToApply(Instant now);

    /** ACTIVE, có ngày kết thúc, ends_at &lt;= threshold (threshold = now − ân hạn). */
    List<Contract> findDueToExpire(Instant threshold);

    /** ACTIVE, không legacy, đã áp dụng/đến ngày bắt đầu và chưa kết thúc (IN_EFFECT): ứng viên nhắc hạn. */
    List<Contract> findReminderCandidates(Instant now);

    /** ACTIVE, không legacy, ends_at &lt;= horizon và chưa quá ân hạn (ends_at &gt; graceFloor). */
    List<Contract> findExpiring(Instant horizon, Instant graceFloor);
}
