package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import java.util.regex.Pattern;

/** Tên role và mã permission đều theo UPPER_SNAKE_CASE. */
public final class NamingRule {

    private static final Pattern UPPER_SNAKE = Pattern.compile("^[A-Z][A-Z0-9_]{1,99}$");

    private NamingRule() {
    }

    public static String requireUpperSnake(String value, String label) {
        if (value == null || !UPPER_SNAKE.matcher(value).matches()) {
            throw new InvalidAccessControlException(label + " phải theo dạng UPPER_SNAKE_CASE (2-100 ký tự): " + value);
        }
        return value;
    }
}
