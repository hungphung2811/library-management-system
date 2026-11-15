package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint nền tảng: chỉ người vận hành có claim {@code platformAdmin=true} được gọi, không cần tenant và không
 * dùng khóa permission thường (claim này không thay thế quyền của endpoint thường).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PlatformAdminOnly {
}
