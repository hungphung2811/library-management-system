package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint vẫn dùng được khi tenant ở chế độ chỉ đọc (ADR-0031 D4): token có claim {@code tenantAccess = READ_ONLY}
 * bị {@code PermissionInterceptor} từ chối 403 {@code TENANT_READ_ONLY} ở mọi request khác GET/HEAD/OPTIONS, trừ
 * method mang annotation này (yêu cầu xuất dữ liệu, đổi tenant, đổi mật khẩu của chính mình, đánh dấu đã đọc thông
 * báo...). Chỉ đặt trên method (không đặt trên class, để mỗi ngoại lệ nhìn thấy được); {@code @PublicEndpoint}
 * (đăng nhập, làm mới token) tự nhiên không bị chặn.
 *
 * <p>Annotation chỉ bỏ qua nhánh chỉ đọc: kiểm khóa quyền, module và tenant vẫn chạy như thường. Không có tác dụng
 * khi {@code lms.security.permission.enabled=false} hoặc token không mang claim {@code tenantAccess}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AllowedWhenReadOnly {
}
