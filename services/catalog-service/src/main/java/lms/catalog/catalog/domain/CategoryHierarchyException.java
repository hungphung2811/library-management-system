package lms.catalog.catalog.domain;

/** Vi phạm cây thể loại: vòng cha-con (CATEGORY_CYCLE) hoặc quá 2 cấp (CATEGORY_TOO_DEEP). */
public class CategoryHierarchyException extends RuntimeException {

    private final String code;

    public CategoryHierarchyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
