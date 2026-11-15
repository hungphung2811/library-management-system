package lms.identity.auth.infrastructure.config;

import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.domain.UserCredentialRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tạo credential cho tài khoản quản trị khởi tạo (userId khớp với bản ghi user_roles được seed trong
 * access-control-service, gắn role SUPER_ADMIN) và gắn membership vào tenant mặc định. Chỉ chạy khi cấu hình mật
 * khẩu và chưa có credential.
 */
@Configuration
public class BootstrapAdminConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminConfiguration.class);

    /**
     * Tenant của tài khoản quản trị khởi tạo lấy từ cấu hình {@code auth.bootstrap-admin.tenant-id} (application.yml
     * trỏ tới tenant mặc định của migration V11); không cứng id tenant trong code. Chỉ ghi credentials và
     * tenant_memberships (bảng toàn cục), không đụng bảng có RLS nên chạy được khi chưa có tenant trong ngữ cảnh.
     */
    @Bean
    ApplicationRunner bootstrapAdmin(RegisterCredentialUseCase register, UserCredentialRepository repository,
                                     @Value("${auth.bootstrap-admin.user-id}") UUID userId,
                                     @Value("${auth.bootstrap-admin.username}") String username,
                                     @Value("${auth.bootstrap-admin.password:}") String password,
                                     @Value("${auth.bootstrap-admin.must-change-password:true}") boolean mustChange,
                                     @Value("${auth.bootstrap-admin.tenant-id}") UUID tenantId) {
        return args -> {
            if (password.isBlank() || repository.existsByUserId(userId)) return;
            register.execute(userId, username, password, null, mustChange, tenantId);
            log.info("Đã tạo tài khoản quản trị khởi tạo '{}'", username);
        };
    }

    /**
     * Tài khoản người vận hành (super admin, cờ credentials.platform_admin, không thuộc tenant nào) từ cấu hình
     * {@code auth.bootstrap.platform-admin-username/password}; bỏ trống một trong hai thì không tạo, đã có tên đăng nhập
     * thì bỏ qua (không nâng tài khoản có sẵn thành platform admin). Chỉ ghi credentials (bảng toàn cục).
     */
    @Bean
    ApplicationRunner bootstrapPlatformAdmin(RegisterCredentialUseCase register, UserCredentialRepository repository,
                                             @Value("${auth.bootstrap.platform-admin-username:}") String username,
                                             @Value("${auth.bootstrap.platform-admin-password:}") String password) {
        return args -> {
            if (username.isBlank() || password.isBlank()) return;
            if (repository.existsByUsername(username.trim().toLowerCase())) return;
            register.registerPlatformAdmin(username, password);
            log.info("Đã tạo tài khoản quản trị nền tảng '{}'", username.trim().toLowerCase());
        };
    }
}
