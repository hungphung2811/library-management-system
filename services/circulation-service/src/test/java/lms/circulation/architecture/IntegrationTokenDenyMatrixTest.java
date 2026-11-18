package lms.circulation.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.security.JwtClaims;
import lms.security.permission.PermissionInterceptor;
import lms.security.permission.PermissionKeyRegistrar;
import lms.security.permission.PermissionKeyResolver;
import lms.security.permission.TenantScopeBinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

/**
 * Ma trận bảo mật (SaaS API Authentication): quét MỌI controller thật của service, dựng PermissionInterceptor thật và
 * kiểm token integration (aud lms-integration) tại từng endpoint: không có {@code @IntegrationScope} thì phải 403
 * FORBIDDEN dù token mang scope đủ, admin, platformAdmin và header X-Tenant-Id; có scope thì token thiếu scope bị
 * INSUFFICIENT_SCOPE, thiếu tenant bị TENANT_REQUIRED. Endpoint {@code @PublicEndpoint} được bỏ qua (công khai).
 */
class IntegrationTokenDenyMatrixTest {

    private static final String SERVICE = "circulation";
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final List<String> ALL_SCOPES = List.of("books:read", "books:write", "inventory:read",
            "inventory:write", "orders:read", "orders:write", "customers:read", "customers:write");

    private final PermissionInterceptor interceptor = new PermissionInterceptor(new PermissionKeyResolver(),
            mock(PermissionKeyRegistrar.class), SERVICE, true, TenantScopeBinder.NOOP, true);
    private final PermissionKeyResolver resolver = new PermissionKeyResolver();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void moiEndpointKhongCoIntegrationScopeTraVe403ChoTokenIntegration() throws Exception {
        List<HandlerMethod> handlers = handlers();
        assertThat(handlers).as("phải quét được controller của service " + SERVICE).isNotEmpty();
        List<String> failures = new ArrayList<>();
        int denied = 0;
        int scoped = 0;
        int publicOnes = 0;
        List<String> scopedList = new ArrayList<>();
        for (HandlerMethod h : handlers) {
            PermissionKeyResolver.Resolved r = resolver.resolve(h);
            String name = h.getBeanType().getSimpleName() + "#" + h.getMethod().getName();
            if (r.mode() == PermissionKeyResolver.Mode.PUBLIC) {
                publicOnes++;
                continue;
            }
            if (r.integrationScope() != null) {
                scoped++;
                scopedList.add(name + " [" + r.integrationScope() + "]");
                continue;
            }
            denied++;
            Result a = call(h, token(String.join(" ", ALL_SCOPES), true, false, false), null);
            if (a.status != 403 || !a.body.contains("\"code\":\"FORBIDDEN\"")) {
                failures.add(name + " (token thường) -> " + a.status + " " + a.body);
            }
            Result b = call(h, token(String.join(" ", ALL_SCOPES), true, true, true), UUID.randomUUID().toString());
            if (b.status != 403 || !b.body.contains("\"code\":\"FORBIDDEN\"")) {
                failures.add(name + " (admin+platformAdmin+X-Tenant-Id) -> " + b.status + " " + b.body);
            }
        }
        System.out.println("[IntegrationTokenDenyMatrix:" + SERVICE + "] endpoint=" + handlers.size() + " deny403="
                + denied + " scoped=" + scoped + " public=" + publicOnes);
        scopedList.forEach(s -> System.out.println("[IntegrationTokenDenyMatrix:" + SERVICE + "] scoped " + s));
        assertThat(failures).as("endpoint để lọt token integration").isEmpty();
    }

    @Test
    void endpointCoScopeThieuScopeHoacThieuTenantBiChanVaChiMoGet() throws Exception {
        List<String> failures = new ArrayList<>();
        for (HandlerMethod h : handlers()) {
            PermissionKeyResolver.Resolved r = resolver.resolve(h);
            if (r.mode() == PermissionKeyResolver.Mode.PUBLIC || r.integrationScope() == null) continue;
            String name = h.getBeanType().getSimpleName() + "#" + h.getMethod().getName();
            Result noScope = call(h, token("", true, false, false), null);
            if (noScope.status != 403 || !noScope.body.contains("INSUFFICIENT_SCOPE")) {
                failures.add(name + " thiếu scope -> " + noScope.status);
            }
            String others = ALL_SCOPES.stream().filter(s -> !s.equals(r.integrationScope()))
                    .reduce((x, y) -> x + " " + y).orElse("");
            Result otherScope = call(h, token(others, true, false, false), null);
            if (otherScope.status != 403) failures.add(name + " scope khác -> " + otherScope.status);
            Result noTenant = call(h, token(r.integrationScope(), false, false, false), null);
            if (noTenant.status != 403 || !noTenant.body.contains("TENANT_REQUIRED")) {
                failures.add(name + " thiếu tenant -> " + noTenant.status + " " + noTenant.body);
            }
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(h.getMethod(), RequestMapping.class);
            boolean readOnly = mapping != null && mapping.method().length > 0
                    && java.util.Arrays.stream(mapping.method())
                            .allMatch(m -> m == RequestMethod.GET || m == RequestMethod.POST);
            if (!readOnly) failures.add(name + " có @IntegrationScope nhưng dùng PUT/PATCH/DELETE hoặc không rõ method");
            if (r.integrationScope().endsWith(":write")) failures.add(name + " dùng scope ghi " + r.integrationScope());
        }
        assertThat(failures).isEmpty();
    }

    private record Result(int status, String body) { }

    private Result call(HandlerMethod h, Jwt jwt, String xTenant) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        if (xTenant != null) request.addHeader("X-Tenant-Id", xTenant);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean allowed = interceptor.preHandle(request, response, h);
        if (allowed) return new Result(200, "ALLOWED");
        return new Result(response.getStatus(), response.getContentAsString());
    }

    private static Jwt token(String scope, boolean withTenant, boolean admin, boolean platformAdmin) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_INTEGRATION));
        if (withTenant) claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.SCOPE, scope);
        claims.put(JwtClaims.MODULES, List.of("LENDING", "SALES"));
        claims.put("permissions", List.of("*"));
        if (admin) claims.put(JwtClaims.ADMIN, true);
        if (platformAdmin) claims.put(JwtClaims.PLATFORM_ADMIN, true);
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("client")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(b::claim);
        return b.build();
    }

    private List<HandlerMethod> handlers() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<HandlerMethod> out = new ArrayList<>();
        ObjenesisStd objenesis = new ObjenesisStd();
        for (BeanDefinition bd : scanner.findCandidateComponents("lms." + SERVICE)) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) continue;
                if (AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) == null) continue;
                out.add(new HandlerMethod(objenesis.newInstance(type), m));
            }
        }
        return out;
    }
}
