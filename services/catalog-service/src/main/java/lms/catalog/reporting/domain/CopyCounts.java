package lms.catalog.reporting.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Số bản sao theo trạng thái của một tenant. Luôn đủ mọi trạng thái (thiếu thì 0) để biểu đồ/ô số không phải xử lý
 * khóa vắng; {@link #total()} là tổng các trạng thái. Tên trạng thái khớp enum bản sao của context inventory
 * (test kiểm khớp), không import để giữ ranh giới context.
 */
public record CopyCounts(Map<String, Long> byStatus) {

    public static final List<String> STATUSES = List.of("AVAILABLE", "LOANED", "RESERVED", "LOST", "DAMAGED");

    public CopyCounts {
        Map<String, Long> full = new LinkedHashMap<>();
        for (String s : STATUSES) {
            Long v = byStatus == null ? null : byStatus.get(s);
            full.put(s, v == null ? 0L : v);
        }
        byStatus = Collections.unmodifiableMap(full);
    }

    public long total() {
        return byStatus.values().stream().mapToLong(Long::longValue).sum();
    }
}
