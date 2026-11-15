package lms.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

class TenantAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TenantAutoConfiguration.class));

    @Test
    void filterDuocDangKyVoiOrderSauChuoiSpringSecurity() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TenantContextFilter.class);
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<TenantContextFilter> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getOrder()).isEqualTo(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
            assertThat(registration.getFilter()).isSameAs(context.getBean(TenantContextFilter.class));
        });
    }

    @Test
    void customizerDatResolverVaoThuocTinhHibernate() {
        runner.run(context -> {
            Map<String, Object> properties = new HashMap<>();

            context.getBean(HibernatePropertiesCustomizer.class).customize(properties);

            assertThat(properties.get("hibernate.tenant_identifier_resolver")).isInstanceOf(TenantIdentifierResolver.class);
        });
    }

    @Test
    void tatLmsTenantEnabledThiKhongCoBeanNao() {
        runner.withPropertyValues("lms.tenant.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TenantContextFilter.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context).doesNotHaveBean(HibernatePropertiesCustomizer.class);
            assertThat(context).doesNotHaveBean(TenantIdentifierResolver.class);
        });
    }
}
