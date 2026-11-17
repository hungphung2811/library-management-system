package lms.api.catalog.dto;

import java.util.UUID;

/** Bộ lọc tìm danh mục: keyword (tên/mô tả), active (null = tất cả), parentId (chỉ thể loại), page từ 0, size 1..100. */
public record ReferenceSearchQuery(String keyword, Boolean active, UUID parentId, Integer page, Integer size) {
}
