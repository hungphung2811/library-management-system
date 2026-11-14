package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.NamingRule;

/** Permission là hành động cụ thể được phép thực hiện, định danh bằng code. */
public record Permission(String code, String description) {

    public Permission {
        NamingRule.requireUpperSnake(code, "Mã permission");
        if (description == null || description.isBlank()) {
            throw new InvalidAccessControlException("Mô tả permission không được trống");
        }
    }
}
