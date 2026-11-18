package lms.catalog.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tác giả có sách mới trong N ngày gần nhất cho storefront (customer-api ghép với top bán chạy). days mặc định 90 chặn
 * 1..365; limit mặc định 200 chặn 1..200. Không trả id/giá/ISBN, chỉ tên hiển thị.
 */
public class ListNewAuthorsUseCase {

    public static final int DEFAULT_DAYS = 90;
    public static final int MAX_DAYS = 365;
    public static final int DEFAULT_LIMIT = 200;
    public static final int MAX_LIMIT = 200;

    private final NewAuthorsQuery query;
    private final Clock clock;

    public ListNewAuthorsUseCase(NewAuthorsQuery query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    public List<String> execute(Integer days, Integer limit) {
        int d = clamp(days, DEFAULT_DAYS, MAX_DAYS);
        int l = clamp(limit, DEFAULT_LIMIT, MAX_LIMIT);
        List<String> rows = query.findAuthorsWithTitlesSince(clock.instant().minus(Duration.ofDays(d)), l);
        // Phòng thủ: bỏ tên rỗng và trùng (không phân biệt hoa thường/khoảng trắng), giữ thứ tự và tên của dòng đầu (mới nhất)
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String name : rows) {
            if (name == null) continue;
            String display = name.strip();
            if (display.isEmpty()) continue;
            distinct.putIfAbsent(display.toLowerCase(Locale.ROOT), display);
        }
        return distinct.values().stream().limit(l).toList();
    }

    private static int clamp(Integer value, int def, int max) {
        if (value == null) return def;
        return Math.max(1, Math.min(max, value));
    }
}
