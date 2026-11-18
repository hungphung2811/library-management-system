package lms.circulation.fine.domain;

/**
 * Vi phạm quy tắc nghiệp vụ của khoản phạt (miễn/giảm, hoàn khoản, bậc hỏng...). code là mã lỗi trả về cho FE; kind chọn
 * nhóm mã HTTP (INVALID 422, NOT_FOUND 404, CONFLICT 409) ở lớp web.
 */
public class FineRuleException extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, CONFLICT }

    private final Kind kind;
    private final String code;

    public FineRuleException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static FineRuleException invalid(String code, String message) {
        return new FineRuleException(Kind.INVALID, code, message);
    }

    public static FineRuleException notFound(String code, String message) {
        return new FineRuleException(Kind.NOT_FOUND, code, message);
    }

    public static FineRuleException conflict(String code, String message) {
        return new FineRuleException(Kind.CONFLICT, code, message);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
