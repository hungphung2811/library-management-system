package lms.integration.common.dto;

import java.util.List;

/** Trang kết quả v1: {@code page} bắt đầu từ 0, {@code total} là tổng số phần tử. */
public record PageView<T>(List<T> items, long total, int page, int size) {

    public PageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
