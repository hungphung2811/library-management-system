package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint mà token tích hợp máy-với-máy (claim {@code aud} chứa {@code lms-integration}) được phép gọi, kèm scope công
 * khai cần có trong claim {@code scope} của token (ví dụ {@code books:read}). Đặt trên method (ArchUnit chỉ cho method
 * {@code @*Mapping} trong {@code infrastructure.web}) hoặc class.
 *
 * <p>Cộng thêm vào chế độ sẵn có, không thay thế: token không phải integration giữ nguyên luồng cũ. Với token
 * integration: endpoint không mang annotation này thì 403 {@code FORBIDDEN} (kể cả {@code @AuthenticatedOnly},
 * {@code @PlatformAdminOnly}, {@code @StorefrontAccessible}); thiếu {@code tenantId} thì 403 {@code TENANT_REQUIRED};
 * thiếu module thì 403 {@code MODULE_NOT_ENABLED}; thiếu scope thì 403 {@code INSUFFICIENT_SCOPE}; còn lại cho qua,
 * không đăng ký khóa, không kiểm khóa {@code Controller/method}.
 *
 * <p>Scope là hợp đồng công khai với bên ngoài (chuỗi cố định có phiên bản), khác khóa quyền nội bộ nên không vi phạm
 * ADR-0003. Không có tác dụng khi {@code lms.security.permission.enabled=false}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IntegrationScope {

    /** Scope bắt buộc, ví dụ {@code books:read}. */
    String value();
}
