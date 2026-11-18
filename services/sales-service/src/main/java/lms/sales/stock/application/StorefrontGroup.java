package lms.sales.stock.application;

import lms.sales.shared.domain.SalesException;

/**
 * Cách gộp danh sách storefront (ADR-0026 3.6). {@code EDITION} (mặc định, tương thích cũ): mỗi ấn bản một thẻ;
 * {@code WORK}: gộp các ấn bản cùng tác phẩm thành một thẻ. Allowlist đóng, phân biệt hoa thường.
 */
public enum StorefrontGroup {
    EDITION,
    WORK;

    /** {@code null} (vắng) = {@code EDITION}; giá trị lạ (kể cả chuỗi rỗng) ném INVALID_REQUEST trước khi chạm DB. */
    public static StorefrontGroup parse(String value) {
        if (value == null) {
            return EDITION;
        }
        for (StorefrontGroup g : values()) {
            if (g.name().equals(value)) {
                return g;
            }
        }
        throw SalesException.invalidRequest("group chỉ nhận EDITION hoặc WORK");
    }
}
