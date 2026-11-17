package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint GET danh sách/tìm kiếm cho phép platform admin (token {@code platformAdmin=true}, không có
 * {@code tenantId}) đọc xuyên tenant (ADR-0008). Chỉ có tác dụng khi request là GET; {@link PermissionInterceptor}
 * đọc query {@code tenantId} (có: một tenant, vắng: tất cả tenant). Người gọi khác không bị ảnh hưởng: luồng phân quyền
 * và tenant như thường, tham số {@code tenantId} bị bỏ qua. Chỉ đặt trên method {@code @GetMapping}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CrossTenantReadable {
}
