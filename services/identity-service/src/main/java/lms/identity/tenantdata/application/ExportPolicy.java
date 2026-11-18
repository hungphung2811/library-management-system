package lms.identity.tenantdata.application;

import java.time.Duration;

/**
 * Tham số xuất dữ liệu tenant (ADR-0031 D3): giới hạn 1 lần thành công mỗi {@code rateLimit}, file sống
 * {@code retention}, tối đa {@code maxBytes} dữ liệu thô (tổng byte CSV, chưa nén), RUNNING quá {@code staleAfter}
 * là kẹt, mỗi nguồn thử tối đa {@code maxAttempts} lần cách nhau {@code retryBackoff}.
 */
public record ExportPolicy(Duration rateLimit, Duration retention, long maxBytes, Duration staleAfter,
                           int maxAttempts, Duration retryBackoff, Duration tokenTtl) {

    public ExportPolicy {
        if (rateLimit == null || retention == null || staleAfter == null || retryBackoff == null || tokenTtl == null) {
            throw new IllegalArgumentException("Thiếu tham số xuất dữ liệu");
        }
        if (maxBytes <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("maxBytes và maxAttempts phải dương");
        }
    }

    public static ExportPolicy defaults() {
        return new ExportPolicy(Duration.ofHours(24), Duration.ofHours(72), 2L * 1024 * 1024 * 1024,
                Duration.ofHours(2), 3, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }
}
