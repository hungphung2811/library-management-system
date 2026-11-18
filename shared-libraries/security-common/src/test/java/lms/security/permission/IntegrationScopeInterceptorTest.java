package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

/** Token integration (aud lms-integration) x chế độ endpoint, và bảo đảm token STAFF/STOREFRONT/svc không đổi hành vi. */
class IntegrationScopeInterceptorTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @RestController
    static class BooksController {
        @IntegrationScope("books:read")
        @GetMapping("/books")
        public String books() { return "ok"; }

        @IntegrationScope("books:read")
        @RequiresModule(TenantModule.SALES)
        @GetMapping("/books/sales")
        public String salesBooks() { return "ok"; }

        @IntegrationScope("books:read")
        @AuthenticatedOnly
        @GetMapping("/books/me")
        public String me() { return "ok"; }

        @IntegrationScope("books:read")
        @StorefrontAccessible
        @GetMapping("/books/shop")
        public String shop() { return "ok"; }

        @IntegrationScope("books:read")
        @PublicEndpoint
        @GetMapping("/books/public")
        public String publicOne() { return "ok"; }

        @IntegrationScope("books:read")
        @PlatformAdminOnly
        @GetMapping("/books/platform")
        public String platform() { return "ok"; }

        @IntegrationScope("books:write")
        @GetMapping("/books/write")
        public String write() { return "ok"; }

        // Không có @IntegrationScope
        @GetMapping("/plain")
        public String plain() { return "ok"; }

        @AuthenticatedOnly
        @GetMapping("/plain/me")
        public String plainMe() { return "ok"; }

        @PlatformAdminOnly
        @GetMapping("/plain/platform")
        public String plainPlatform() { return "ok"; }

        @StorefrontAccessible
        @GetMapping("/plain/shop")
        public String plainShop() { return "ok"; }

        @PublicEndpoint
        @GetMapping("/plain/public")
        public String plainPublic() { return "ok"; }
    }

    @RestController
    @IntegrationScope("orders:read")
    static class OrdersController {
        @GetMapping("/orders")
        public String orders() { return "ok"; }

        @IntegrationScope("inventory:read")
        @GetMapping("/orders/override")
        public String override() { return "ok"; }
    }

    private final PermissionKeyRegistrar registrar = mock(PermissionKeyRegistrar.class);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new BooksController(), new OrdersController())
                .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), registrar, "sample",
                        true, TenantScopeBinder.NOOP, true))
                .build();
    }

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(Map<String, Object> claims, String... authorities) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(jwt::claim);
        List<GrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), granted));
    }

    private static Map<String, Object> integrationToken(String scope, Object modules) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_INTEGRATION));
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.SCOPE, scope);
        claims.put(JwtClaims.CLIENT_ID, UUID.randomUUID().toString());
        if (modules != null) claims.put(JwtClaims.MODULES, modules);
        return claims;
    }

    private static ResultMatcher code(String code) {
        return content().string(org.hamcrest.Matchers.containsString("\"code\":\"" + code + "\""));
    }

    @Test
    void scopeDungQuaVaKhongDangKyKhoa() throws Exception {
        login(integrationToken("books:read orders:read", null));

        mvc().perform(get("/books")).andExpect(status().isOk());
        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void scopeNamGiuaChuoiNhieuScopeVanQua() throws Exception {
        login(integrationToken("inventory:read   books:read", null));

        mvc().perform(get("/books")).andExpect(status().isOk());
    }

    @Test
    void scopeSaiBi403InsufficientScope() throws Exception {
        login(integrationToken("orders:read", null));

        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void scopeGhiKhongSuyRaTuScopeDoc() throws Exception {
        login(integrationToken("books:read", null));

        mvc().perform(get("/books/write")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void scopeChiDungChuoiConKhongDuocTinh() throws Exception {
        login(integrationToken("xbooks:read books:reader", null));

        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void thieuClaimScopeBi403() throws Exception {
        Map<String, Object> claims = integrationToken("x", null);
        claims.remove(JwtClaims.SCOPE);
        login(claims);

        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void scopeLaMangThiKhongDuocChapNhan() throws Exception {
        Map<String, Object> claims = integrationToken("x", null);
        claims.put(JwtClaims.SCOPE, List.of("books:read"));
        login(claims);

        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void khongTenantBi403TenantRequiredTruocKiemScope() throws Exception {
        Map<String, Object> claims = integrationToken("orders:read", null);
        claims.remove(JwtClaims.TENANT_ID);
        login(claims);

        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("TENANT_REQUIRED"));
    }

    @Test
    void moduleBatThiQuaModuleTatThi403KhongFallbackLending() throws Exception {
        login(integrationToken("books:read", List.of("SALES")));
        mvc().perform(get("/books/sales")).andExpect(status().isOk());

        login(integrationToken("books:read", List.of("LENDING")));
        mvc().perform(get("/books/sales")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));

        login(integrationToken("books:read", null));
        mvc().perform(get("/books/sales")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void moduleDuocKiemTruocScope() throws Exception {
        login(integrationToken("orders:read", List.of("LENDING")));

        mvc().perform(get("/books/sales")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void endpointKhongCoIntegrationScopeLuon403ForbiddenKhongLoKhoa() throws Exception {
        login(integrationToken("books:read orders:read", List.of("SALES")), "BooksController/plain");

        for (String path : List.of("/plain", "/plain/me", "/plain/platform", "/plain/shop")) {
            mvc().perform(get(path)).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("BooksController"))));
        }
        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void integrationScopeTrenAuthenticatedOnlyVaStorefrontVanTheoDungScope() throws Exception {
        login(integrationToken("books:read", null));
        mvc().perform(get("/books/me")).andExpect(status().isOk());
        mvc().perform(get("/books/shop")).andExpect(status().isOk());

        login(integrationToken("orders:read", null));
        mvc().perform(get("/books/me")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void platformAdminOnlyKhongBaoGioMoChoTokenIntegrationDuClaimCoGi() throws Exception {
        Map<String, Object> claims = integrationToken("books:read", List.of("SALES"));
        claims.put(JwtClaims.PLATFORM_ADMIN, true);
        claims.put(JwtClaims.ADMIN, true);
        claims.put(JwtClaims.SERVICE, JwtClaims.SERVICE_CUSTOMER_API);
        login(claims);

        mvc().perform(get("/plain/platform")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        // Endpoint có @IntegrationScope + @PlatformAdminOnly: mở theo scope, không theo cờ platformAdmin
        mvc().perform(get("/books/platform")).andExpect(status().isOk());
        // Có admin=true cũng không bỏ qua kiểm scope
        mvc().perform(get("/books/write")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void adminTrongClaimKhongBoQuaScope() throws Exception {
        Map<String, Object> claims = integrationToken("orders:read", null);
        claims.put(JwtClaims.ADMIN, true);
        login(claims);

        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void actAsTenantHeaderKhongCoTacDungVoiTokenIntegration() throws Exception {
        Map<String, Object> claims = integrationToken("books:read", null);
        claims.put(JwtClaims.PLATFORM_ADMIN, true);
        claims.remove(JwtClaims.TENANT_ID);
        login(claims);

        mvc().perform(get("/books").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isForbidden()).andExpect(code("TENANT_REQUIRED"));
    }

    @Test
    void publicEndpointVanMoChoTokenIntegration() throws Exception {
        login(integrationToken("", null));

        mvc().perform(get("/books/public")).andExpect(status().isOk());
        mvc().perform(get("/plain/public")).andExpect(status().isOk());
    }

    @Test
    void chuaDangNhapVan401() throws Exception {
        mvc().perform(get("/books")).andExpect(status().isUnauthorized());
    }

    @Test
    void annotationTrenClassCoHieuLucVaMethodThangClass() throws Exception {
        login(integrationToken("orders:read", null));
        mvc().perform(get("/orders")).andExpect(status().isOk());
        mvc().perform(get("/orders/override")).andExpect(status().isForbidden()).andExpect(code("INSUFFICIENT_SCOPE"));
    }

    @Test
    void audienceDangChuoiDonCungNhanDienLaIntegration() throws Exception {
        Map<String, Object> claims = integrationToken("books:read", null);
        claims.put(JwtClaims.AUDIENCE, JwtClaims.AUDIENCE_INTEGRATION);
        login(claims, "BooksController/plain");

        mvc().perform(get("/plain")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    // Token khác không đổi hành vi

    @Test
    void tokenNhanVienTrenEndpointIntegrationScopeVanKiemKhoaCu() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        login(claims);
        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));

        login(claims, "BooksController/books");
        mvc().perform(get("/books")).andExpect(status().isOk());
        verify(registrar, org.mockito.Mockito.atLeastOnce()).ensureRegistered(any());
    }

    @Test
    void tokenNhanVienAdminVanQuaEndpointIntegrationScope() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.ADMIN, true);
        login(claims);

        mvc().perform(get("/books")).andExpect(status().isOk());
        mvc().perform(get("/plain")).andExpect(status().isOk());
    }

    @Test
    void tokenStorefrontAudKhongDoiHanhVi() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_STOREFRONT));
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.SCOPE, "books:read");
        login(claims, "BooksController/plain");

        mvc().perform(get("/plain")).andExpect(status().isOk());
        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void tokenSvcKhongDoiHanhViVaKhongDuocMoBoiIntegrationScope() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.SERVICE, JwtClaims.SERVICE_CUSTOMER_API);
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.MODULES, List.of("SALES"));
        claims.put(JwtClaims.SCOPE, "books:read");
        login(claims);

        mvc().perform(get("/plain/shop")).andExpect(status().isOk());
        mvc().perform(get("/books/shop")).andExpect(status().isOk());
        mvc().perform(get("/books")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc().perform(get("/plain/me")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void resolverDocIntegrationScopeTheoMethodHoacClass() throws Exception {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        assertThat(resolver.resolve(new HandlerMethod(new BooksController(), BooksController.class.getMethod("books")))
                .integrationScope()).isEqualTo("books:read");
        assertThat(resolver.resolve(new HandlerMethod(new BooksController(), BooksController.class.getMethod("plain")))
                .integrationScope()).isNull();
        assertThat(resolver.resolve(new HandlerMethod(new OrdersController(), OrdersController.class.getMethod("orders")))
                .integrationScope()).isEqualTo("orders:read");
        assertThat(resolver.resolve(new HandlerMethod(new OrdersController(),
                OrdersController.class.getMethod("override"))).integrationScope()).isEqualTo("inventory:read");
        PermissionKeyResolver.Resolved resolved = resolver.resolve(
                new HandlerMethod(new BooksController(), BooksController.class.getMethod("books")));
        assertThat(resolved.mode()).isEqualTo(PermissionKeyResolver.Mode.PERMISSION);
        assertThat(resolved.key()).isEqualTo("BooksController/books");
    }
}
