package lms.identity.tenant.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * Composition root của context tenant. Chưa có use case ở bước này (adapter persistence tự đăng ký bằng
 * Repository); use case chọn tenant và quản trị tenant sẽ khai báo bean tại đây ở các task sau.
 */
@Configuration
public class TenantBeanConfiguration {
}
