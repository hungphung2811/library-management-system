package lms.identity.tenantdata.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lms.identity.tenantdata.domain.ExportStatus;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportException;
import lms.identity.tenantdata.domain.TenantDataExportException.Reason;
import lms.identity.tenantdata.domain.TenantDataExportRepository;

/**
 * Mở luồng tải gói xuất (đã giải mã theo luồng). Chỉ gói READY còn hạn của đúng tenant; mỗi lần tải ghi download_count,
 * last_downloaded_at và audit (ai tải, lúc nào). Có thể tải nhiều lần trong hạn.
 */
public class DownloadTenantDataExportUseCase {

    /** Luồng ZIP + tên file gợi ý + kích thước ZIP (byte). Người gọi phải đóng luồng. */
    public record Download(InputStream stream, String filename, long sizeBytes) {
    }

    private final TenantDataExportRepository exports;
    private final ExportStoragePort storage;
    private final ExportAuditor auditor;
    private final Clock clock;

    public DownloadTenantDataExportUseCase(TenantDataExportRepository exports, ExportStoragePort storage,
                                           lms.identity.tenant.application.PublishPlatformEventPort events,
                                           Clock clock) {
        this.exports = exports;
        this.storage = storage;
        this.auditor = new ExportAuditor(events);
        this.clock = clock;
    }

    public Download open(UUID tenantId, UUID id, UUID actor, boolean byPlatform) {
        TenantDataExport e = exports.find(tenantId, id).orElseThrow(QueryTenantDataExportUseCase::notFound);
        Instant now = clock.instant();
        if (e.status() == ExportStatus.EXPIRED || (e.status() == ExportStatus.READY && !e.downloadable(now))) {
            throw new TenantDataExportException(Reason.EXPORT_EXPIRED, "Gói xuất đã hết hạn");
        }
        if (e.status() != ExportStatus.READY || e.storageKey() == null) {
            throw new TenantDataExportException(Reason.EXPORT_NOT_READY, "Gói xuất chưa sẵn sàng");
        }
        if (!storage.configured()) {
            throw new TenantDataExportException(Reason.EXPORT_NOT_CONFIGURED, "Tính năng xuất dữ liệu chưa được cấu hình");
        }
        if (!storage.exists(e.storageKey())) {
            throw new TenantDataExportException(Reason.EXPORT_EXPIRED, "Gói xuất đã hết hạn");
        }
        InputStream in;
        try {
            in = storage.open(e.storageKey());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        exports.recordDownload(e.id(), now);
        auditor.record(ExportAuditor.DOWNLOADED, actor, byPlatform, tenantId, e.id(),
                byPlatform ? "platform" : "tenant");
        return new Download(in, "tenant-data-" + e.id() + ".zip", e.sizeBytes());
    }
}
