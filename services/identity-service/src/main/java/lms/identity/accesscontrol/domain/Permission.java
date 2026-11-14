package lms.identity.accesscontrol.domain;

import java.time.Instant;

/**
 * Permission là một khóa quyền, định danh bằng code. Có hai nguồn:
 * CUSTOM là mã BE chủ đích đặt (ví dụ BORROW_BOOK), AUTO là khóa tự sinh theo endpoint (TênController/tênPhươngThức)
 * được đăng ký khi endpoint được gọi lần đầu. Các trường service, httpMethod, pathPattern, firstSeenAt cho biết
 * khóa này thuộc endpoint nào, phục vụ người quản trị khi gán quyền.
 */
public record Permission(String code, String description, String source, String service, String httpMethod,
                         String pathPattern, Instant firstSeenAt) {

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_CUSTOM = "CUSTOM";

    public Permission {
        NamingRule.requirePermissionKey(code, "Mã permission");
        if (description == null || description.isBlank()) {
            throw new InvalidAccessControlException("Mô tả permission không được trống");
        }
        if (!SOURCE_AUTO.equals(source) && !SOURCE_CUSTOM.equals(source)) {
            throw new InvalidAccessControlException("Nguồn permission phải là AUTO hoặc CUSTOM: " + source);
        }
    }

    /** Permission do người quản trị hoặc BE tạo thủ công, chưa gắn với endpoint nào. */
    public Permission(String code, String description) {
        this(code, description, SOURCE_CUSTOM, null, null, null, null);
    }
}
