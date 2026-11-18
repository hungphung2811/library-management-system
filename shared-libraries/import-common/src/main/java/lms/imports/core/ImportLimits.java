package lms.imports.core;

import java.time.Duration;

/**
 * Các giới hạn của luồng nhập (mặc định theo ADR-0024; ghi đè bằng {@code lms.import.*}).
 *
 * @param maxRows            tổng số dòng dữ liệu tối đa (các sheet cộng lại)
 * @param maxFileBytes       kích thước file tối đa
 * @param maxColumns         số cột tối đa đọc mỗi sheet
 * @param maxCellChars       độ dài ô tối đa
 * @param maxZipEntries      số entry tối đa trong file zip
 * @param maxInflatedBytes   tổng byte giải nén tối đa
 * @param maxEntryBytes      byte giải nén tối đa mỗi entry
 * @param minInflateRatio    tỉ lệ nén tối thiểu (nén/giải nén) của một entry đủ lớn
 * @param maxRowsBlobBytes   kích thước tối đa của dữ liệu dòng sau gzip khi lưu phiên
 * @param sessionTtl         TTL phiên
 * @param maxSessionsPerUser số phiên mở tối đa mỗi người trong một tenant
 * @param chunkSize          số dòng mỗi chunk khi confirm
 * @param uploadConcurrency  số lần đọc file đồng thời tối đa mỗi instance
 * @param commitQueue        số lần confirm chờ tối đa trong hàng đợi nền
 * @param heartbeatStale     quá thời gian này không heartbeat thì phiên COMMITTING hiển thị INTERRUPTED
 * @param maxPageSize        kích thước trang tối đa của {@code rows}
 */
public record ImportLimits(int maxRows, long maxFileBytes, int maxColumns, int maxCellChars, int maxZipEntries,
                           long maxInflatedBytes, long maxEntryBytes, double minInflateRatio, int maxRowsBlobBytes,
                           Duration sessionTtl, int maxSessionsPerUser, int chunkSize, int uploadConcurrency,
                           int commitQueue, Duration heartbeatStale, int maxPageSize) {

    public static ImportLimits defaults() {
        return new ImportLimits(5_000, 5L * 1024 * 1024, 50, 2_000, 200, 100L * 1024 * 1024, 50L * 1024 * 1024, 0.01,
                4 * 1024 * 1024, Duration.ofMinutes(30), 3, 200, 2, 4, Duration.ofMinutes(2), 200);
    }
}
