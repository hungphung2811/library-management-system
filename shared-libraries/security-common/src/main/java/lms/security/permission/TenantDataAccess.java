package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint nội bộ xuất/xóa dữ liệu theo tenant (ADR-0031 D1), chỉ dành cho token dịch vụ {@code svc = tenant-data}
 * do identity ký ({@code aud = lms-tenant-data}, TTL ngắn, có {@code tenantId}). Chỉ đặt trên method
 * {@code @*Mapping} của {@code infrastructure.web} (luật {@code TenantDataArchitectureRules}).
 *
 * <p>Quy tắc ở {@code PermissionInterceptor}, đặt trước mọi nhánh token khác:
 * <ul>
 *   <li>Token {@code svc = tenant-data} (kèm {@code aud} đúng và {@code tenantId}): vào được, không kiểm khóa, không
 *       đăng ký khóa, không kiểm module (tenant đã thu hồi module vẫn xuất/xóa được dữ liệu).</li>
 *   <li>Mọi token khác (người dùng kể cả admin/platform admin, storefront {@code svc}, integration): 403
 *       {@code FORBIDDEN}, thông điệp không nêu khóa nội bộ.</li>
 *   <li>Ngược lại, token {@code tenant-data} ở endpoint không mang annotation này: 403 {@code FORBIDDEN}.</li>
 * </ul>
 * Annotation thắng {@code @PublicEndpoint} (endpoint mang cả hai vẫn đòi token tenant-data). Không có tác dụng khi
 * {@code lms.security.permission.enabled=false}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TenantDataAccess {
}
