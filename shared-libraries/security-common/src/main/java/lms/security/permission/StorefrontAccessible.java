package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint mà token dịch vụ storefront (claim {@code svc}, ADR-0011 mục 2.1) được phép gọi. Đặt trên controller
 * (class) hoặc method; chỉ cần một trong hai.
 *
 * <p>Cộng thêm vào chế độ sẵn có của endpoint, không thay thế: với token người dùng (không có claim {@code svc}) luồng
 * cũ giữ nguyên, endpoint vẫn kiểm khóa {@code Controller/method}. Với token {@code svc}: endpoint không mang
 * annotation này thì 403 {@code FORBIDDEN} (kể cả {@code @AuthenticatedOnly}, {@code @PlatformAdminOnly}); có thì
 * kiểm {@code tenantId} (403 {@code TENANT_REQUIRED}) và {@code @RequiresModule} (403 {@code MODULE_NOT_ENABLED}),
 * còn lại cho qua, không đăng ký khóa, không kiểm khóa.
 *
 * <p>Endpoint dùng annotation này không được lấy người thực hiện từ {@code CurrentUser} (sub của token dịch vụ là
 * UUID dành riêng). Không có tác dụng khi {@code lms.security.permission.enabled=false}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface StorefrontAccessible {
}
