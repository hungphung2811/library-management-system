package lms.security.permission;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bật middleware phân quyền theo endpoint cho mọi service servlet có phụ thuộc security-common.
 * Tắt bằng {@code lms.security.permission.enabled=false}. Một service có thể tự khai báo
 * {@link PermissionKeyRegistrar} riêng (identity-service ghi thẳng vào DB của mình).
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.HandlerInterceptor")
@ConditionalOnProperty(prefix = "lms.security.permission", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PermissionProperties.class)
public class PermissionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PermissionAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    PermissionKeyResolver permissionKeyResolver() {
        return new PermissionKeyResolver();
    }

    @Bean
    @ConditionalOnMissingBean(PermissionKeyRegistrar.class)
    PermissionKeyRegistrar remotePermissionKeyRegistrar(PermissionProperties properties) {
        if (properties.getIdentityBaseUrl().isBlank() || properties.getInternalApiKey().isBlank()) {
            log.warn("Chưa cấu hình lms.security.permission.identity-base-url / internal-api-key: "
                    + "khóa quyền của endpoint sẽ KHÔNG được đăng ký vào danh mục permission");
            return descriptor -> { };
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        RestClient client = RestClient.builder().baseUrl(properties.getIdentityBaseUrl()).requestFactory(factory).build();
        return new RemotePermissionKeyRegistrar(client, properties.getInternalApiKey(),
                CachingPermissionKeyRegistrar.backgroundExecutor(), Clock.systemUTC(), properties.getRegistrationCooldown());
    }

    @Bean
    PermissionInterceptor permissionInterceptor(PermissionKeyResolver resolver, PermissionKeyRegistrar registrar,
                                                PermissionProperties properties) {
        return new PermissionInterceptor(resolver, registrar, properties.getServiceName());
    }

    @Bean
    WebMvcConfigurer permissionWebMvcConfigurer(PermissionInterceptor interceptor, PermissionProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns("/**")
                        .excludePathPatterns(properties.getExcludePaths());
            }
        };
    }
}
