package lms.identity.apiclient.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bảng toàn cục applications + application_scopes (identity_db, không RLS): MỌI truy vấn theo Application đều kèm
 * tenantId (chống IDOR); Application đã xóa mềm (DELETED) không bao giờ được trả về.
 */
public interface ApplicationRepository {

    /** Ghi (thêm hoặc cập nhật) cùng tập scope, flush ngay. Trùng tên trong tenant → APPLICATION_NAME_DUPLICATED. */
    Application save(Application application);

    /**
     * Tra theo id KHÔNG biết tenant (client_id của token endpoint OAuth2, bảng toàn cục); DELETED không trả về. Chỉ dùng
     * cho xác thực client; mọi đường quản trị vẫn phải theo tenant.
     */
    Optional<Application> findVisibleById(UUID id);

    Optional<Application> findByIdAndTenant(UUID id, UUID tenantId);

    /** Như {@link #findByIdAndTenant} nhưng khóa dòng (SELECT ... FOR UPDATE) tới hết transaction hiện tại. */
    Optional<Application> findByIdAndTenantForUpdate(UUID id, UUID tenantId);

    /** Application chưa xóa của tenant, theo ngày tạo tăng dần (scope nạp theo lô, không N+1). */
    List<Application> findByTenant(UUID tenantId);

    /** Khóa các dòng chưa xóa của tenant (tuần tự hóa tạo mới khi đã có dòng) và trả số lượng. */
    int lockAndCountByTenant(UUID tenantId);

    /** Tên đã dùng trong tenant (không phân biệt hoa thường, bỏ qua dòng đã xóa và {@code excludeId}). */
    boolean existsName(UUID tenantId, String name, UUID excludeId);

    boolean existsSiteKey(String siteKey);
}
