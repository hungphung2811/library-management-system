package lms.identity.tenantdata.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng lưu yêu cầu xuất (bảng toàn cục tenant_data_exports, không RLS): mọi truy vấn theo tenant lọc {@code tenant_id}
 * tường minh; người gọi đưa tenant từ TenantContext, không từ đầu vào client.
 */
public interface TenantDataExportRepository {

    /** Thêm mới; vi phạm chỉ mục "một yêu cầu đang chạy mỗi tenant" thì ném EXPORT_IN_PROGRESS. */
    void insert(TenantDataExport export);

    /** Chỉ trả khi yêu cầu thuộc đúng tenant (chống IDOR). */
    Optional<TenantDataExport> find(UUID tenantId, UUID id);

    Optional<TenantDataExport> findById(UUID id);

    /** Mới nhất trước. */
    List<TenantDataExport> listByTenant(UUID tenantId, int limit);

    boolean hasInProgress(UUID tenantId);

    /** Có yêu cầu thành công (READY hoặc EXPIRED) kết thúc sau {@code since}. */
    boolean hasSuccessSince(UUID tenantId, Instant since);

    List<UUID> pendingIds(int limit);

    /** Cập nhật có điều kiện PENDING sang RUNNING; false nếu đã có worker khác nhận. */
    boolean claim(UUID id, Instant now);

    void update(TenantDataExport export);

    /** RUNNING sang PENDING (sau khởi động lại: không còn worker nào giữ việc). Trả số yêu cầu. */
    int requeueRunning();

    List<TenantDataExport> findRunningStartedBefore(Instant threshold);

    List<TenantDataExport> findReadyExpiredBefore(Instant now, int limit);

    /** Tăng download_count và đặt last_downloaded_at nguyên tử. */
    void recordDownload(UUID id, Instant now);
}
