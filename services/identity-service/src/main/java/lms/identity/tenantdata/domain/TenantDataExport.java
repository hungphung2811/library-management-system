package lms.identity.tenantdata.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Yêu cầu xuất toàn bộ dữ liệu của một tenant (ADR-0031). Vòng đời: PENDING, RUNNING, READY, EXPIRED; hoặc FAILED.
 * Không giữ thông điệp lỗi thô (chỉ {@code errorCode}) và không lộ đường dẫn lưu trữ ra ngoài.
 */
public final class TenantDataExport {

    private final UUID id;
    private final UUID tenantId;
    private ExportStatus status;
    private final UUID requestedBy;
    private final boolean requestedByPlatform;
    private final String reason;
    private final Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant expiresAt;
    private int partsTotal;
    private int partsDone;
    private long sizeBytes;
    private String sha256;
    private String storageKey;
    private String errorCode;
    private int downloadCount;
    private Instant lastDownloadedAt;

    private TenantDataExport(UUID id, UUID tenantId, ExportStatus status, UUID requestedBy,
                             boolean requestedByPlatform, String reason, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.status = status;
        this.requestedBy = requestedBy;
        this.requestedByPlatform = requestedByPlatform;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public static TenantDataExport request(UUID tenantId, UUID requestedBy, boolean byPlatform, String reason,
                                           Instant now) {
        if (tenantId == null || requestedBy == null) {
            throw new IllegalArgumentException("tenantId và requestedBy là bắt buộc");
        }
        String r = reason == null || reason.isBlank() ? null : reason.strip();
        return new TenantDataExport(UUID.randomUUID(), tenantId, ExportStatus.PENDING, requestedBy, byPlatform, r, now);
    }

    @SuppressWarnings("java:S107")
    public static TenantDataExport restore(UUID id, UUID tenantId, ExportStatus status, UUID requestedBy,
                                           boolean requestedByPlatform, String reason, Instant createdAt,
                                           Instant startedAt, Instant finishedAt, Instant expiresAt, int partsTotal,
                                           int partsDone, long sizeBytes, String sha256, String storageKey,
                                           String errorCode, int downloadCount, Instant lastDownloadedAt) {
        TenantDataExport e = new TenantDataExport(id, tenantId, status, requestedBy, requestedByPlatform, reason,
                createdAt);
        e.startedAt = startedAt;
        e.finishedAt = finishedAt;
        e.expiresAt = expiresAt;
        e.partsTotal = partsTotal;
        e.partsDone = partsDone;
        e.sizeBytes = sizeBytes;
        e.sha256 = sha256;
        e.storageKey = storageKey;
        e.errorCode = errorCode;
        e.downloadCount = downloadCount;
        e.lastDownloadedAt = lastDownloadedAt;
        return e;
    }

    /** Khóa lưu trữ tất định theo id: không bao giờ lấy từ đầu vào của client (chống path traversal). */
    public static String storageKeyOf(UUID exportId) {
        return "export-" + exportId + ".zip.enc";
    }

    /** Sau khi job nhận việc (cập nhật có điều kiện ở repository); đồng bộ trạng thái trong bộ nhớ. */
    public void markRunning(Instant now, int partsTotal) {
        transition(ExportStatus.RUNNING, ExportStatus.PENDING, ExportStatus.RUNNING);
        this.partsTotal = partsTotal;
        this.partsDone = 0;
        if (this.startedAt == null) {
            this.startedAt = now;
        }
    }

    public void partDone() {
        transition(ExportStatus.RUNNING, ExportStatus.RUNNING);
        partsDone++;
    }

    public void ready(long sizeBytes, String sha256, String storageKey, Instant now, Duration retention) {
        transition(ExportStatus.READY, ExportStatus.RUNNING);
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.finishedAt = now;
        this.expiresAt = now.plus(retention);
        this.errorCode = null;
    }

    public void fail(String errorCode, Instant now) {
        if (status == ExportStatus.READY || status == ExportStatus.EXPIRED) {
            throw new IllegalStateException("Yêu cầu đã " + status + ", không thể chuyển FAILED");
        }
        this.status = ExportStatus.FAILED;
        this.errorCode = errorCode;
        this.finishedAt = now;
        this.storageKey = null;
        this.expiresAt = null;
    }

    public void expire(Instant now) {
        transition(ExportStatus.EXPIRED, ExportStatus.READY);
        this.storageKey = null;
        if (finishedAt == null) {
            finishedAt = now;
        }
    }

    /** READY và còn hạn tại thời điểm {@code now}. */
    public boolean downloadable(Instant now) {
        return status == ExportStatus.READY && expiresAt != null && expiresAt.isAfter(now);
    }

    /** Đã thành công (READY hoặc EXPIRED) và xong sau {@code since}: tính vào giới hạn 1 lần/24 giờ. */
    public boolean succeededSince(Instant since) {
        return (status == ExportStatus.READY || status == ExportStatus.EXPIRED) && finishedAt != null
                && finishedAt.isAfter(since);
    }

    private void transition(ExportStatus next, ExportStatus... allowedFrom) {
        for (ExportStatus s : allowedFrom) {
            if (status == s) {
                this.status = next;
                return;
            }
        }
        throw new IllegalStateException("Chuyển trạng thái không hợp lệ: " + status + " -> " + next);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public ExportStatus status() { return status; }
    public UUID requestedBy() { return requestedBy; }
    public boolean requestedByPlatform() { return requestedByPlatform; }
    public String reason() { return reason; }
    public Instant createdAt() { return createdAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public Instant expiresAt() { return expiresAt; }
    public int partsTotal() { return partsTotal; }
    public int partsDone() { return partsDone; }
    public long sizeBytes() { return sizeBytes; }
    public String sha256() { return sha256; }
    public String storageKey() { return storageKey; }
    public String errorCode() { return errorCode; }
    public int downloadCount() { return downloadCount; }
    public Instant lastDownloadedAt() { return lastDownloadedAt; }
}
