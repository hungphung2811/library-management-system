package lms.identity.tenantdata.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.identity.tenantdata.domain.ExportStatus;
import lms.identity.tenantdata.domain.TenantDataExport;

/**
 * Bảng toàn cục tenant_data_exports (không @TenantId, nằm trong TENANT_EXEMPT_TABLES): job nền quét mọi tenant; chống IDOR
 * bằng lọc (tenant_id, id) tường minh ở repository.
 */
@Entity
@Table(name = "tenant_data_exports")
class TenantDataExportJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String status;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_by_platform", nullable = false, updatable = false)
    private boolean requestedByPlatform;

    @Column(updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "parts_total", nullable = false)
    private int partsTotal;

    @Column(name = "parts_done", nullable = false)
    private int partsDone;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256")
    private String sha256;

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "error_code")
    private String errorCode;

    // download_count/last_downloaded_at chỉ đổi qua truy vấn cập nhật nguyên tử (insertable/updatable=false ở merge)
    @Column(name = "download_count", nullable = false, updatable = false)
    private int downloadCount;

    @Column(name = "last_downloaded_at", updatable = false)
    private Instant lastDownloadedAt;

    protected TenantDataExportJpaEntity() {
    }

    static TenantDataExportJpaEntity from(TenantDataExport e) {
        TenantDataExportJpaEntity j = new TenantDataExportJpaEntity();
        j.id = e.id();
        j.tenantId = e.tenantId();
        j.requestedBy = e.requestedBy();
        j.requestedByPlatform = e.requestedByPlatform();
        j.reason = e.reason();
        j.createdAt = e.createdAt();
        j.downloadCount = e.downloadCount();
        j.lastDownloadedAt = e.lastDownloadedAt();
        j.apply(e);
        return j;
    }

    void apply(TenantDataExport e) {
        this.status = e.status().name();
        this.startedAt = e.startedAt();
        this.finishedAt = e.finishedAt();
        this.expiresAt = e.expiresAt();
        this.partsTotal = e.partsTotal();
        this.partsDone = e.partsDone();
        this.sizeBytes = e.sizeBytes();
        this.sha256 = e.sha256();
        this.storageKey = e.storageKey();
        this.errorCode = e.errorCode();
    }

    TenantDataExport toDomain() {
        return TenantDataExport.restore(id, tenantId, ExportStatus.valueOf(status), requestedBy, requestedByPlatform,
                reason, createdAt, startedAt, finishedAt, expiresAt, partsTotal, partsDone, sizeBytes, sha256,
                storageKey, errorCode, downloadCount, lastDownloadedAt);
    }

    UUID getId() { return id; }
}
