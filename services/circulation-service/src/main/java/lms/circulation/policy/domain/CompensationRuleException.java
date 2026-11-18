package lms.circulation.policy.domain;

/**
 * Vi phạm quy tắc cấu hình/áp dụng bậc hỏng và chính sách đền bù. code là mã lỗi trả về cho FE; kind chọn nhóm mã HTTP
 * ở lớp web (INVALID 422, NOT_FOUND 404, CONFLICT 409).
 */
public class CompensationRuleException extends RuntimeException {

    public enum Kind { INVALID, NOT_FOUND, CONFLICT }

    private final Kind kind;
    private final String code;

    public CompensationRuleException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static CompensationRuleException invalid(String code, String message) {
        return new CompensationRuleException(Kind.INVALID, code, message);
    }

    public static CompensationRuleException notFound(String code, String message) {
        return new CompensationRuleException(Kind.NOT_FOUND, code, message);
    }

    public static CompensationRuleException conflict(String code, String message) {
        return new CompensationRuleException(Kind.CONFLICT, code, message);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }
}
