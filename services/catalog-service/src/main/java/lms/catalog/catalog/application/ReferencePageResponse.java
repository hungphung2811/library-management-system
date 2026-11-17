package lms.catalog.catalog.application;

import java.util.List;

/** Một trang kết quả tìm; page đếm từ 0. */
public record ReferencePageResponse(List<ReferenceResponse> items, long total, int page, int size) {
}
