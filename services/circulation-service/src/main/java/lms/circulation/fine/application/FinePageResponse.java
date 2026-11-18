package lms.circulation.fine.application;

import java.util.List;

/** Trang kết quả: total là tổng số dòng khớp bộ lọc, page tính từ 0. */
public record FinePageResponse<T>(List<T> items, long total, int page, int size) {

    static int pageOf(Integer page) {
        return page == null || page < 0 ? 0 : page;
    }

    static int sizeOf(Integer size) {
        if (size == null) return 20;
        return Math.max(1, Math.min(100, size));
    }
}
