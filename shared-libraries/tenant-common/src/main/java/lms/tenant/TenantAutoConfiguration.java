package lms.tenant;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bật TenantContextFilter, lớp bọc DataSource đặt {@code app.tenant_id} cho RLS và (nếu có Hibernate) bộ phân giải
 * tenant. Tắt toàn bộ bằng {@code lms.tenant.enabled=false}.
 */
@AutoConfiguration(before = lms.security.permission.PermissionAutoConfiguration.class)
@ConditionalOnProperty(prefix = "lms.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TenantAutoConfiguration {

    /** Cài đặt SPI của security-common: interceptor gắn phạm vi đọc xuyên tenant qua TenantContext (ADR-0008). */
    @Bean
    @ConditionalOnMissingBean(lms.security.permission.TenantScopeBinder.class)
    lms.security.permission.TenantScopeBinder tenantScopeBinder() {
        return new lms.security.permission.TenantScopeBinder() {
            @Override
            public void bindAll() {
                TenantContext.bindAllTenants();
            }

            @Override
            public void bindSingle(java.util.UUID tenantId) {
                TenantContext.set(tenantId);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass({Filter.class, org.springframework.security.oauth2.jwt.Jwt.class})
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TenantContextFilter tenantContextFilter() {
            return new TenantContextFilter();
        }

        /** Chạy ngay sau chuỗi filter Spring Security (nơi JWT được xác thực). */
        @Bean
        FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(TenantContextFilter filter) {
            FilterRegistrationBean<TenantContextFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
            return registration;
        }
    }

    /** Lớp 2 (RLS): bọc mọi DataSource để đặt {@code app.tenant_id}. Tắt riêng bằng {@code lms.tenant.rls.enabled=false}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "lms.tenant.rls", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class RlsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        static TenantRlsConnectionInitializer tenantRlsConnectionInitializer() {
            return new TenantRlsConnectionInitializer();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({org.hibernate.context.spi.CurrentTenantIdentifierResolver.class, HibernatePropertiesCustomizer.class})
    static class HibernateConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TenantIdentifierResolver tenantIdentifierResolver() {
            return new TenantIdentifierResolver();
        }

        @Bean
        HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(TenantIdentifierResolver resolver) {
            return properties -> properties.put("hibernate.tenant_identifier_resolver", resolver);
        }
    }
}
