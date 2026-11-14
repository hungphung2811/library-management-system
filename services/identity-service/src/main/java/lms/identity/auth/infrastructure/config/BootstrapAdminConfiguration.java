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
 * access-control-service, gắn role SUPER_ADMIN). Chỉ chạy khi cấu hình mật khẩu và chưa có credential.
 */
@Configuration
public class BootstrapAdminConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminConfiguration.class);

    @Bean
    ApplicationRunner bootstrapAdmin(RegisterCredentialUseCase register, UserCredentialRepository repository,
                                     @Value("${auth.bootstrap-admin.user-id}") UUID userId,
                                     @Value("${auth.bootstrap-admin.username}") String username,
                                     @Value("${auth.bootstrap-admin.password:}") String password) {
        return args -> {
            if (password.isBlank() || repository.existsByUserId(userId)) return;
            register.execute(userId, username, password, null);
            log.info("Đã tạo tài khoản quản trị khởi tạo '{}'", username);
        };
    }
}
