package lms.system.menu.domain;

import java.util.UUID;

/** Phần dữ liệu có thể đổi của một mục (dùng cho tạo và sửa; {@code code} chỉ đặt lúc tạo). */
public record MenuDetails(
        String label,
        String path,
        String icon,
        UUID parentId,
        int sortOrder,
        boolean enabled,
        String permissionKey) {}
