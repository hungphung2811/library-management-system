package lms.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lms.api.dashboard.service.DashboardExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** ThreadLocal của luồng request (token, X-Tenant-Id, correlationId) phải sang được luồng con và bị xóa khi xong. */
class ContextCopyingTaskDecoratorTest {

    private record Seen(Object token, String tenantHeader, String correlationId, String thread) {
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        MDC.clear();
    }

    private static void bindRequestContext() {
        Jwt jwt = Jwt.withTokenValue("token-nguoi-dung").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant-dich");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MDC.put("correlationId", "corr-1");
    }

    private static Seen observe() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String tenant = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                ? a.getRequest().getHeader("X-Tenant-Id") : null;
        return new Seen(auth instanceof JwtAuthenticationToken t ? t.getToken().getTokenValue() : null, tenant,
                MDC.get("correlationId"), Thread.currentThread().getName());
    }

    @Test
    void luongConThayDuocTokenTenantVaCorrelationId() throws Exception {
        DashboardExecutor executor = new DashboardExecutor(2, 2, 4);
        try {
            bindRequestContext();
            Seen seen = executor.submit(ContextCopyingTaskDecoratorTest::observe).get(5, TimeUnit.SECONDS);

            assertEquals("token-nguoi-dung", seen.token());
            assertEquals("tenant-dich", seen.tenantHeader());
            assertEquals("corr-1", seen.correlationId());
            assertNotEquals(Thread.currentThread().getName(), seen.thread());
            assertEquals(true, seen.thread().startsWith("dashboard-"));
        } finally {
            executor.destroy();
        }
    }

    @Test
    void doiChungKhongDecoratorLuongConMatTatCa() throws Exception {
        // chứng minh test trên có ý nghĩa: pool thường không chép ThreadLocal
        ThreadPoolTaskExecutor plain = new ThreadPoolTaskExecutor();
        plain.setCorePoolSize(1);
        plain.initialize();
        try {
            bindRequestContext();
            Seen seen = CompletableFuture.supplyAsync(ContextCopyingTaskDecoratorTest::observe, plain)
                    .get(5, TimeUnit.SECONDS);
            assertNull(seen.token());
            assertNull(seen.tenantHeader());
            assertNull(seen.correlationId());
        } finally {
            plain.shutdown();
        }
    }

    @Test
    void luongPoolDuocXoaSachSauKhiXong() throws Exception {
        DashboardExecutor executor = new DashboardExecutor(1, 1, 4); // một luồng duy nhất, dùng lại giữa hai request
        try {
            bindRequestContext();
            assertNotNull(executor.submit(ContextCopyingTaskDecoratorTest::observe).get(5, TimeUnit.SECONDS).token());

            clear(); // request thứ hai không có ngữ cảnh
            Seen second = executor.submit(ContextCopyingTaskDecoratorTest::observe).get(5, TimeUnit.SECONDS);
            assertNull(second.token());
            assertNull(second.tenantHeader());
            assertNull(second.correlationId());
        } finally {
            executor.destroy();
        }
    }
}
