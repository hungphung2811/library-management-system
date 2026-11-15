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

/** Bật TenantContextFilter và (nếu có Hibernate) bộ phân giải tenant. Tắt bằng {@code lms.tenant.enabled=false}. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "lms.tenant", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TenantAutoConfiguration {

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
