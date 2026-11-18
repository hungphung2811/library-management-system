package lms.imports.autoconfigure;

import java.time.Duration;
import lms.imports.core.ImportLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình {@code lms.import.*}; mặc định theo ADR-0024. Ghi đè bằng biến môi trường (ví dụ LMS_IMPORT_MAX_ROWS). */
@ConfigurationProperties(prefix = "lms.import")
public class ImportProperties {

    private final ImportLimits d = ImportLimits.defaults();

    private int maxRows = d.maxRows();
    private long maxFileBytes = d.maxFileBytes();
    private int maxColumns = d.maxColumns();
    private int maxCellChars = d.maxCellChars();
    private int maxZipEntries = d.maxZipEntries();
    private long maxInflatedBytes = d.maxInflatedBytes();
    private long maxEntryBytes = d.maxEntryBytes();
    private double minInflateRatio = d.minInflateRatio();
    private int maxRowsBlobBytes = d.maxRowsBlobBytes();
    private Duration sessionTtl = d.sessionTtl();
    private int maxSessionsPerUser = d.maxSessionsPerUser();
    private int chunkSize = d.chunkSize();
    private int uploadConcurrency = d.uploadConcurrency();
    private int commitQueue = d.commitQueue();
    private Duration heartbeatStale = d.heartbeatStale();
    private int maxPageSize = d.maxPageSize();

    public ImportLimits toLimits() {
        return new ImportLimits(maxRows, maxFileBytes, maxColumns, maxCellChars, maxZipEntries, maxInflatedBytes,
                maxEntryBytes, minInflateRatio, maxRowsBlobBytes, sessionTtl, maxSessionsPerUser, chunkSize,
                uploadConcurrency, commitQueue, heartbeatStale, maxPageSize);
    }

    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int v) { this.maxRows = v; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long v) { this.maxFileBytes = v; }
    public int getMaxColumns() { return maxColumns; }
    public void setMaxColumns(int v) { this.maxColumns = v; }
    public int getMaxCellChars() { return maxCellChars; }
    public void setMaxCellChars(int v) { this.maxCellChars = v; }
    public int getMaxZipEntries() { return maxZipEntries; }
    public void setMaxZipEntries(int v) { this.maxZipEntries = v; }
    public long getMaxInflatedBytes() { return maxInflatedBytes; }
    public void setMaxInflatedBytes(long v) { this.maxInflatedBytes = v; }
    public long getMaxEntryBytes() { return maxEntryBytes; }
    public void setMaxEntryBytes(long v) { this.maxEntryBytes = v; }
    public double getMinInflateRatio() { return minInflateRatio; }
    public void setMinInflateRatio(double v) { this.minInflateRatio = v; }
    public int getMaxRowsBlobBytes() { return maxRowsBlobBytes; }
    public void setMaxRowsBlobBytes(int v) { this.maxRowsBlobBytes = v; }
    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration v) { this.sessionTtl = v; }
    public int getMaxSessionsPerUser() { return maxSessionsPerUser; }
    public void setMaxSessionsPerUser(int v) { this.maxSessionsPerUser = v; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int v) { this.chunkSize = v; }
    public int getUploadConcurrency() { return uploadConcurrency; }
    public void setUploadConcurrency(int v) { this.uploadConcurrency = v; }
    public int getCommitQueue() { return commitQueue; }
    public void setCommitQueue(int v) { this.commitQueue = v; }
    public Duration getHeartbeatStale() { return heartbeatStale; }
    public void setHeartbeatStale(Duration v) { this.heartbeatStale = v; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int v) { this.maxPageSize = v; }
}
