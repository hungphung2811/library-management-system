package lms.security.permission;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint thuộc một module của tenant (ADR-0009). Đặt trên controller (class); method có thể ghi đè bằng annotation
 * riêng (method thắng class). Token không có module trong claim {@code modules} bị 403 {@code MODULE_NOT_ENABLED},
 * kể cả {@code admin=true} và kể cả endpoint {@code @AuthenticatedOnly}. Không có tác dụng khi
 * {@code lms.security.permission.enabled=false}.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresModule {
    TenantModule value();
}
