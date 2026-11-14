package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Interceptor bỏ qua endpoint này (không kiểm đăng nhập, không kiểm permission, không đăng ký khóa).
 * Chỉ là đánh dấu cho interceptor: việc cho phép gọi không cần token vẫn phải khai báo ở SecurityFilterChain.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface PublicEndpoint {
}
