package lms.catalog.catalog.application;

import java.util.UUID;

/** Bộ lọc tìm danh mục: keyword (tên/mô tả), active (null = tất cả), parentId (chỉ thể loại), page từ 0, size 1..100 (mặc định 20). */
public record ReferenceSearchQuery(String keyword, Boolean active, UUID parentId, Integer page, Integer size) {

    public int pageOrDefault() {
        return page == null || page < 0 ? 0 : page;
    }

    public int sizeOrDefault() {
        return size == null || size < 1 ? 20 : Math.min(size, 100);
    }
}
