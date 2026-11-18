package lms.api.tenantdata.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lms.api.tenantdata.dto.TenantDataDtos.ExportRequest;
import lms.api.tenantdata.dto.TenantDataDtos.ExportView;

/**
 * Cổng sang /internal/tenant-data/exports của identity-service (ADR-0031 F2). Khóa quyền
 * {@code TenantDataExportController/*}, tenant (từ token) và chống IDOR kiểm ở identity; client chỉ chuyển tiếp, 4xx chuyển
 * nguyên, 5xx thành 502.
 */
public interface TenantDataClient {

    ExportView request(ExportRequest request);

    List<ExportView> list();

    ExportView get(UUID id);

    /** Tải gói của chính tenant trong token. {@code handler} chỉ được gọi khi identity trả 200. */
    void download(UUID id, DownloadHandler handler);

    /**
     * Platform admin tải gói của một tenant (đường dẫn identity {@code /api/platform/tenants/{tenantId}/data-exports/...});
     * header {@code X-Tenant-Id} không cần thiết vì tenant nằm trong đường dẫn.
     */
    void downloadForTenant(UUID tenantId, UUID id, DownloadHandler handler);

    /** Nhận luồng tải; PHẢI đọc/đóng luồng trong hàm này (kết nối đóng ngay sau khi hàm trả về). */
    @FunctionalInterface
    interface DownloadHandler {

        void handle(Download download) throws IOException;
    }

    /** Đầu ra của identity: ZIP đã giải mã, đọc theo luồng (không nạp cả gói vào bộ nhớ). */
    record Download(String contentType, Long contentLength, String contentDisposition, InputStream body) {
    }
}
