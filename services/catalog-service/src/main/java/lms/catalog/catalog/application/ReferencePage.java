package lms.catalog.catalog.application;

import java.util.List;
import java.util.function.Function;

/** Một trang kết quả tìm với phần tử đã đổi dạng theo loại danh mục; page đếm từ 0. */
public record ReferencePage<T>(List<T> items, long total, int page, int size) {

    public static <T> ReferencePage<T> of(ReferencePageResponse p, Function<ReferenceResponse, T> mapper) {
        return new ReferencePage<>(p.items().stream().map(mapper).toList(), p.total(), p.page(), p.size());
    }
}
