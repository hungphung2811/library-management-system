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

/** ADR-0011 mục 2.1: bảng quyết định token dịch vụ (svc) x annotation x tenant x module x chế độ. */
class StorefrontAccessibleInterceptorTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @RestController
    static class PlainController {
        @GetMapping("/plain")
        public String plain() { return "ok"; }

        @AuthenticatedOnly
        @GetMapping("/plain/me")
        public String me() { return "ok"; }

        @PlatformAdminOnly
        @GetMapping("/plain/platform")
        public String platform() { return "ok"; }

        @PublicEndpoint
        @GetMapping("/plain/public")
        public String publicOne() { return "ok"; }

        @RequiresModule(TenantModule.SALES)
        @GetMapping("/plain/sales")
        public String sales() { return "ok"; }
    }

    @RestController
    @RequiresModule(TenantModule.SALES)
    static class ShopController {
        @StorefrontAccessible
        @GetMapping("/shop/items")
        public String items() { return "ok"; }

        @GetMapping("/shop/orders")
        public String orders() { return "ok"; }
    }

    @RestController
    @StorefrontAccessible
    static class OpenOnClassController {
        @GetMapping("/open")
        public String open() { return "ok"; }

        @AuthenticatedOnly
        @GetMapping("/open/me")
        public String me() { return "ok"; }

        @PlatformAdminOnly
        @GetMapping("/open/platform")
        public String platform() { return "ok"; }
    }

    private final PermissionKeyRegistrar registrar = mock(PermissionKeyRegistrar.class);

    private MockMvc mvc(boolean requireTenant) {
        return MockMvcBuilders.standaloneSetup(new PlainController(), new ShopController(), new OpenOnClassController())
                .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), registrar, "sample",
                        requireTenant, TenantScopeBinder.NOOP, true))
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

    private static Map<String, Object> svcToken(Object modules) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.SERVICE, JwtClaims.SERVICE_CUSTOMER_API);
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        if (modules != null) claims.put(JwtClaims.MODULES, modules);
        claims.put(JwtClaims.PERMISSIONS, List.of());
        return claims;
    }

    private static ResultMatcher code(String code) {
        return content().string(org.hamcrest.Matchers.containsString("\"code\":\"" + code + "\""));
    }

    @Test
    void svcQuaEndpointStorefrontCoTenantVaModule() throws Exception {
        login(svcToken(List.of("SALES")));

        mvc(true).perform(get("/shop/items")).andExpect(status().isOk());

        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void svcThieuModuleBi403ModuleNotEnabled() throws Exception {
        login(svcToken(List.of("LENDING")));

        mvc(true).perform(get("/shop/items")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void svcKhongCoClaimModulesBi403KhongApDungFallbackLending() throws Exception {
        login(svcToken(null));

        mvc(true).perform(get("/shop/items")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void svcKhongTenantBi403TenantRequiredTruocKiemModule() throws Exception {
        Map<String, Object> claims = svcToken(List.of("SALES"));
        claims.remove(JwtClaims.TENANT_ID);
        login(claims);

        mvc(false).perform(get("/shop/items")).andExpect(status().isForbidden()).andExpect(code("TENANT_REQUIRED"));
    }

    @Test
    void svcTrenEndpointKhongDanhDauBi403Forbidden() throws Exception {
        login(svcToken(List.of("SALES")), "PlainController/plain", "ShopController/orders");

        mvc(true).perform(get("/plain")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc(true).perform(get("/shop/orders")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc(true).perform(get("/plain/sales")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void svcBi403FordbiddenTrenAuthenticatedOnlyVaPlatformAdminOnly() throws Exception {
        login(svcToken(List.of("SALES")));

        mvc(true).perform(get("/plain/me")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc(true).perform(get("/plain/platform")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void svcCoPlatformAdminTrongClaimVanKhongQuaPlatformAdminOnly() throws Exception {
        Map<String, Object> claims = svcToken(List.of("SALES"));
        claims.put(JwtClaims.PLATFORM_ADMIN, true);
        login(claims);

        mvc(true).perform(get("/plain/platform")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void svcThongBaoLoi403KhongLoTenKhoa() throws Exception {
        login(svcToken(List.of("SALES")));

        mvc(true).perform(get("/plain")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PlainController"))));
    }

    @Test
    void annotationTrenClassCoHieuLucChoMoiMethod() throws Exception {
        login(svcToken(List.of()));

        mvc(true).perform(get("/open")).andExpect(status().isOk());
        mvc(true).perform(get("/open/me")).andExpect(status().isOk());
        mvc(true).perform(get("/open/platform")).andExpect(status().isOk());
    }

    @Test
    void publicEndpointVanChoMoiTokenQuaKeCaSvc() throws Exception {
        login(svcToken(List.of()));

        mvc(true).perform(get("/plain/public")).andExpect(status().isOk());
    }

    @Test
    void chuaDangNhapVan401() throws Exception {
        mvc(true).perform(get("/shop/items")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenNguoiDungTrenEndpointStorefrontVanKiemKhoa() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.MODULES, List.of("SALES"));
        login(claims);
        mvc(true).perform(get("/shop/items")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));

        login(claims, "ShopController/items");
        mvc(true).perform(get("/shop/items")).andExpect(status().isOk());
    }

    @Test
    void tokenNguoiDungVanKiemModuleTrenEndpointStorefront() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.MODULES, List.of("LENDING"));
        login(claims, "ShopController/items");

        mvc(true).perform(get("/shop/items")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void tokenNguoiDungTrenEndpointThuongGiuNguyenHanhViCu() throws Exception {
        login(Map.of(JwtClaims.TENANT_ID, TENANT.toString()), "PlainController/plain");

        mvc(true).perform(get("/plain")).andExpect(status().isOk());
    }

    @Test
    void resolverTraCoStorefrontAccessibleTheoMethodHoacClass() throws Exception {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        assertThat(resolver.resolve(new HandlerMethod(new ShopController(), ShopController.class.getMethod("items")))
                .storefrontAccessible()).isTrue();
        assertThat(resolver.resolve(new HandlerMethod(new ShopController(), ShopController.class.getMethod("orders")))
                .storefrontAccessible()).isFalse();
        assertThat(resolver.resolve(new HandlerMethod(new OpenOnClassController(),
                OpenOnClassController.class.getMethod("open"))).storefrontAccessible()).isTrue();
        PermissionKeyResolver.Resolved plain = resolver.resolve(
                new HandlerMethod(new PlainController(), PlainController.class.getMethod("plain")));
        assertThat(plain.storefrontAccessible()).isFalse();
        assertThat(plain.mode()).isEqualTo(PermissionKeyResolver.Mode.PERMISSION);
        assertThat(plain.key()).isEqualTo("PlainController/plain");
    }
}
