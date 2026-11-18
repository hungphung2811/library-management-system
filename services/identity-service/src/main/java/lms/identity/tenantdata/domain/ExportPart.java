package lms.identity.tenantdata.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tiến độ xuất của một nguồn (identity, catalog, ...). KHÔNG lưu thông điệp lỗi thô, chỉ mã lỗi chung. */
public final class ExportPart {

    private final UUID exportId;
    private final String service;
    private PartStatus status;
    private int attempts;
    private List<ExportedTable> tables;
    private String errorCode;
    private Instant finishedAt;

    private ExportPart(UUID exportId, String service, PartStatus status, int attempts, List<ExportedTable> tables,
                       String errorCode, Instant finishedAt) {
        this.exportId = exportId;
        this.service = service;
        this.status = status;
        this.attempts = attempts;
        this.tables = tables == null ? List.of() : List.copyOf(tables);
        this.errorCode = errorCode;
        this.finishedAt = finishedAt;
    }

    public static ExportPart pending(UUID exportId, String service) {
        return new ExportPart(exportId, service, PartStatus.PENDING, 0, List.of(), null, null);
    }

    public static ExportPart restore(UUID exportId, String service, PartStatus status, int attempts,
                                     List<ExportedTable> tables, String errorCode, Instant finishedAt) {
        return new ExportPart(exportId, service, status, attempts, tables, errorCode, finishedAt);
    }

    public void attempt() {
        attempts++;
    }

    public void done(List<ExportedTable> exported, Instant now) {
        this.status = PartStatus.DONE;
        this.tables = List.copyOf(exported);
        this.errorCode = null;
        this.finishedAt = now;
    }

    public void failed(String code, Instant now) {
        this.status = PartStatus.FAILED;
        this.errorCode = code;
        this.finishedAt = now;
    }

    public UUID exportId() { return exportId; }
    public String service() { return service; }
    public PartStatus status() { return status; }
    public int attempts() { return attempts; }
    public List<ExportedTable> tables() { return tables; }
    public String errorCode() { return errorCode; }
    public Instant finishedAt() { return finishedAt; }

    public long totalRows() {
        return tables.stream().mapToLong(ExportedTable::rows).sum();
    }
}
