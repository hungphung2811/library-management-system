package lms.api.catalog.dto;

import java.util.List;

/** Một trang kết quả tìm danh mục (page đếm từ 0). */
public record ReferencePageView(List<ReferenceView> items, long total, int page, int size) {
}
