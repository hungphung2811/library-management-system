package lms.support.audit.integration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Ở IT không có JWT: filter này đặt TenantContext từ header X-Test-Tenant (thay cho claim tenantId). */
@TestConfiguration
class TestTenantFilterConfiguration {

    static final String HEADER = "X-Test-Tenant";

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> testTenantFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        String header = request.getHeader(HEADER);
                        try {
                            if (header != null) TenantContext.set(UUID.fromString(header));
                            chain.doFilter(request, response);
                        } finally {
                            TenantContext.clear();
                        }
                    }
                });
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }
}
