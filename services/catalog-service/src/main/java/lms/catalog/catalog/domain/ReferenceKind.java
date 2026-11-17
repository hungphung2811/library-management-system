package lms.catalog.catalog.domain;

/** Bốn danh mục chuẩn theo tenant. Nhãn tiếng Việt dùng trong thông báo lỗi; code dùng làm mã lỗi. */
public enum ReferenceKind {
    AUTHOR("tác giả", "AUTHOR", 255),
    PUBLISHER("nhà xuất bản", "PUBLISHER", 255),
    CATEGORY("thể loại", "CATEGORY", 255),
    /** Vị trí kệ: trường "tên" của danh mục chính là mã vị trí (API gọi là code). */
    SHELF_LOCATION("vị trí kệ", "SHELF_LOCATION", 64);

    private final String label;
    private final String code;
    private final int maxNameLength;

    ReferenceKind(String label, String code, int maxNameLength) {
        this.label = label;
        this.code = code;
        this.maxNameLength = maxNameLength;
    }

    public String label() { return label; }
    public String code() { return code; }
    public int maxNameLength() { return maxNameLength; }
    public String nameLabel() { return this == SHELF_LOCATION ? "Mã" : "Tên"; }
    public String notFoundCode() { return code + "_NOT_FOUND"; }
    public String duplicateCode() { return this == SHELF_LOCATION ? "DUPLICATE_SHELF_LOCATION_CODE" : "DUPLICATE_" + code + "_NAME"; }
    public String inactiveCode() { return code + "_INACTIVE"; }
    public String invalidCode() { return "INVALID_" + code; }
}
