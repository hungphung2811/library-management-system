package lms.api.tenantdata.dto;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO xuất dữ liệu tenant (ADR-0031 F2), khớp phản hồi của identity-service. Tiến độ = partsDone/partsTotal; {@code
 * errorCode} chỉ là mã chung (không có thông điệp lỗi nội bộ); không có đường dẫn lưu trữ.
 */
public final class TenantDataDtos {

    private TenantDataDtos() {
    }

    /** Lý do tùy chọn (hiển thị trong lịch sử và audit). */
    public record ExportRequest(@Size(max = 500) String reason) {
    }

    /** status: PENDING, RUNNING, READY, FAILED, EXPIRED. */
    public record ExportView(UUID id, String status, UUID requestedBy, boolean requestedByPlatform, String reason,
                             Instant createdAt, Instant startedAt, Instant finishedAt, Instant expiresAt,
                             int partsTotal, int partsDone, long sizeBytes, String sha256, String errorCode,
                             int downloadCount, Instant lastDownloadedAt, List<PartView> parts) {
    }

    /** Tiến độ một nguồn (identity, catalog, circulation, sales, support); status: PENDING, DONE, FAILED. */
    public record PartView(String service, String status, int attempts, long rows, String errorCode,
                           Instant finishedAt) {
    }
}
