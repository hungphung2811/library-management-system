package lms.identity.tenantdata.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mô hình manifest.json của gói xuất (ADR-0031 D2). Không có thông điệp lỗi, secret hay email. */
public final class ManifestModel {

    public static final int SCHEMA_VERSION = 1;

    private ManifestModel() {
    }

    public record Manifest(int schemaVersion, UUID exportId, UUID tenantId, String tenantCode, UUID requestedBy,
                           boolean requestedByPlatform, Instant requestedAt, Instant generatedAt,
                           List<Section> sections, List<Excluded> excluded, List<String> notConfigured,
                           String appVersion) {
    }

    /** Phần của một service: files là các CSV của phần đó. */
    public record Section(String service, int schemaVersion, List<File> files) {
    }

    public record File(String path, String table, long rows, long bytes, String sha256, List<String> columns) {
    }

    /** Bảng bị loại có chủ đích kèm lý do. */
    public record Excluded(String service, String table, String reason) {
    }
}
