package lms.sales.stock.application;

import lms.sales.shared.domain.SalesException;

/**
 * Thứ tự sắp danh sách storefront (ADR-0019 mục 2.2). Allowlist đóng: adapter chọn mệnh đề order by bằng switch trên
 * enum này, không bao giờ nối chuỗi từ request.
 */
public enum StorefrontSort {
    NEWEST("newest"),
    PRICE_ASC("price_asc"),
    PRICE_DESC("price_desc");

    private final String param;

    StorefrontSort(String param) {
        this.param = param;
    }

    public String param() {
        return param;
    }

    public boolean byPrice() {
        return this != NEWEST;
    }

    /**
     * {@code null} (vắng) trả {@code null} nghĩa là "mặc định theo ngữ cảnh". Phân biệt hoa thường; giá trị lạ (kể cả
     * chuỗi rỗng) ném {@code INVALID_REQUEST}, trước khi chạm DB.
     */
    public static StorefrontSort parse(String value) {
        if (value == null) {
            return null;
        }
        for (StorefrontSort s : values()) {
            if (s.param.equals(value)) {
                return s;
            }
        }
        throw SalesException.invalidRequest("sort chỉ nhận newest, price_asc, price_desc");
    }
}
