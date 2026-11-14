package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đặt khóa quyền tùy chọn cho endpoint (customKey, do BE chủ đích). Endpoint không gắn annotation nào sẽ dùng
 * khóa tự sinh {@code TênController/tênPhươngThức}. Đặt ở method thì thắng đặt ở class.
 * Khóa chỉ gồm chữ, số và các ký tự {@code _ . / : -}, tối đa 200 ký tự.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequirePermission {

    /** customKey; để trống thì dùng khóa tự sinh. */
    String value() default "";

    /** Mô tả hiển thị cho người quản trị khi gán quyền; để trống thì tự sinh từ HTTP method và đường dẫn. */
    String description() default "";
}
