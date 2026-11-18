package lms.identity.tenantdata.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.tenantdata.domain.ExportPart;
import lms.identity.tenantdata.domain.ExportStatus;
import lms.identity.tenantdata.domain.PartStatus;
import lms.identity.tenantdata.domain.TenantDataExport;

/**
 * Phản hồi cho FE. KHÔNG có storageKey, thông điệp lỗi thô hay đường dẫn; lỗi chỉ là mã chung (errorCode). Tiến độ là
 * partsDone/partsTotal, chi tiết từng nguồn ở {@code parts}.
 */
public final class ExportViews {

    private ExportViews() {
    }

    public record PartView(String service, PartStatus status, int attempts, long rows, String errorCode,
                           Instant finishedAt) {
    }

    public record ExportView(UUID id, ExportStatus status, UUID requestedBy, boolean requestedByPlatform,
                             String reason, Instant createdAt, Instant startedAt, Instant finishedAt,
                             Instant expiresAt, int partsTotal, int partsDone, long sizeBytes, String sha256,
                             String errorCode, int downloadCount, Instant lastDownloadedAt, List<PartView> parts) {
    }

    static ExportView of(TenantDataExport e, List<ExportPart> parts) {
        List<PartView> pv = parts == null ? List.of() : parts.stream()
                .map(p -> new PartView(p.service(), p.status(), p.attempts(), p.totalRows(), p.errorCode(),
                        p.finishedAt()))
                .toList();
        return new ExportView(e.id(), e.status(), e.requestedBy(), e.requestedByPlatform(), e.reason(), e.createdAt(),
                e.startedAt(), e.finishedAt(), e.expiresAt(), e.partsTotal(), e.partsDone(), e.sizeBytes(),
                e.sha256(), e.errorCode(), e.downloadCount(), e.lastDownloadedAt(), pv);
    }
}
