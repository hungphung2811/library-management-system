package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import java.util.regex.Pattern;

/** Tên role và mã permission đều theo UPPER_SNAKE_CASE. */
public final class NamingRule {

    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]{1,99}$");

    /**
     * Khóa permission: gồm cả mã tự đặt kiểu BORROW_BOOK và khóa tự sinh theo endpoint kiểu
     * BorrowingController/borrow. Cùng ký tự với bộ xác định khóa ở security-common.
     */
    private static final Pattern PERMISSION_KEY = Pattern.compile("^[A-Za-z0-9_./:-]{1,200}$");

    private NamingRule() {
    }

    public static String requirePermissionKey(String value, String label) {
        if (value == null || !PERMISSION_KEY.matcher(value).matches()) {
            throw new InvalidAccessControlException(
                    label + " chỉ gồm chữ, số và các ký tự _ . / : - (tối đa 200 ký tự): " + value);
        }
        return value;
    }

    public static String requireUpperSnake(String value, String label) {
        if (value == null || !UPPER_SNAKE.matcher(value).matches()) {
            throw new InvalidAccessControlException(label + " phải theo dạng UPPER_SNAKE_CASE (2-100 ký tự): " + value);
        }
        return value;
    }
}
