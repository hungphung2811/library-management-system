package lms.identity.tenant.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bảng toàn cục tenant_storefront_origins và lịch sử (identity_db, không RLS: lọc tenant_id tường minh). */
public interface StorefrontOriginRepository {

    /** Origin của tenant, theo thời điểm tạo tăng dần. */
    List<StorefrontOriginEntry> findByTenant(UUID tenantId);

    /** Chuỗi origin ĐANG BẬT của tenant (đã chuẩn hóa), theo thứ tự tạo. */
    List<String> findEnabledOrigins(UUID tenantId);

    /**
     * Chuỗi origin ĐANG BẬT gắn đúng Application này (application_id = :applicationId, đã chuẩn hóa), theo thứ tự tạo. Origin
     * cấp tenant (application_id IS NULL) thuộc đường {code} cũ và KHÔNG nằm trong kết quả (pha P2 site key).
     */
    List<String> findEnabledOriginsOfApplication(UUID tenantId, UUID applicationId);

    /** Tenant đang sở hữu chuỗi origin này (kể cả dòng đang tắt). */
    Optional<UUID> findOwnerTenant(String origin);

    /** @throws StorefrontOriginDuplicatedException khi vi phạm UNIQUE(origin) do ghi đồng thời */
    void insert(StorefrontOriginEntry entry);

    void update(StorefrontOriginEntry entry);

    void delete(UUID originId);

    void appendHistory(StorefrontOriginHistoryEntry entry);
}
