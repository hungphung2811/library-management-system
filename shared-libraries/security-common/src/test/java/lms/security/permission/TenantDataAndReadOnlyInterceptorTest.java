package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

/**
 * ADR-0031 (gói F0): chế độ chỉ đọc (claim tenantAccess) và token dịch vụ tenant-data (@TenantDataAccess).
 * Ma trận: loại token x phương thức x chế độ endpoint.
 */
class TenantDataAndReadOnlyInterceptorTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @RestController
    static class PlainController {
        @GetMapping("/plain")
        public String read() { return "ok"; }

        @PostMapping("/plain")
        public String create() { return "ok"; }

        @PutMapping("/plain")
        public String update() { return "ok"; }

        @DeleteMapping("/plain")
        public String remove() { return "ok"; }

        @AllowedWhenReadOnly
        @PostMapping("/plain/allowed")
        public String allowed() { return "ok"; }

        @PublicEndpoint
        @PostMapping("/plain/public")
        public String publicWrite() { return "ok"; }

        @AuthenticatedOnly
        @PostMapping("/plain/me")
        public String meWrite() { return "ok"; }

        @AuthenticatedOnly
        @AllowedWhenReadOnly
        @PostMapping("/plain/me/allowed")
        public String meAllowed() { return "ok"; }

        @PlatformAdminOnly
        @PostMapping("/plain/platform")
        public String platformWrite() { return "ok"; }
    }

    @RestController
    @RequiresModule(TenantModule.SALES)
    static class ShopController {
        @StorefrontAccessible
        @GetMapping("/shop/items")
        public String items() { return "ok"; }

        @StorefrontAccessible
        @PostMapping("/shop/orders")
        public String place() { return "ok"; }
    }

    @RestController
    static class IntegrationController {
        @IntegrationScope("orders:write")
        @PostMapping("/int/orders")
        public String write() { return "ok"; }
    }

    @RestController
    static class DataController {
        @TenantDataAccess
        @GetMapping("/data/export")
        public String export() { return "ok"; }

        @TenantDataAccess
        @PostMapping("/data/purge")
        public String purge() { return "ok"; }

        @RequiresModule(TenantModule.SALES)
        @TenantDataAccess
        @GetMapping("/data/sales")
        public String sales() { return "ok"; }

        @PublicEndpoint
        @TenantDataAccess
        @GetMapping("/data/public")
        public String publicToo() { return "ok"; }
    }

    private final PermissionKeyRegistrar registrar = mock(PermissionKeyRegistrar.class);
    private final PermissionInterceptor interceptor = new PermissionInterceptor(new PermissionKeyResolver(), registrar,
            "sample", true, TenantScopeBinder.NOOP, true);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new PlainController(), new ShopController(),
                        new IntegrationController(), new DataController())
                .addInterceptors(interceptor).build();
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

    private static Map<String, Object> user(String tenantAccess) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.MODULES, List.of("SALES", "LENDING"));
        if (tenantAccess != null) claims.put(JwtClaims.TENANT_ACCESS, tenantAccess);
        return claims;
    }

    private static Map<String, Object> admin(String tenantAccess) {
        Map<String, Object> claims = user(tenantAccess);
        claims.put(JwtClaims.ADMIN, true);
        return claims;
    }

    private static Map<String, Object> storefrontSvc(String tenantAccess) {
        Map<String, Object> claims = user(tenantAccess);
        claims.put(JwtClaims.SERVICE, JwtClaims.SERVICE_CUSTOMER_API);
        return claims;
    }

    private static Map<String, Object> integration(String tenantAccess) {
        Map<String, Object> claims = user(tenantAccess);
        claims.put(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_INTEGRATION));
        claims.put(JwtClaims.SCOPE, "orders:write");
        return claims;
    }

    private static Map<String, Object> tenantData() {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.SERVICE, JwtClaims.SERVICE_TENANT_DATA);
        claims.put(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_TENANT_DATA));
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.PERMISSIONS, List.of());
        return claims;
    }

    private static ResultMatcher code(String code) {
        return content().string(org.hamcrest.Matchers.containsString("\"code\":\"" + code + "\""));
    }

    // ---------- Chế độ chỉ đọc ----------

    @Test
    void chiDocChanPostPutDeleteTrenEndpointThuongKeCaCoKhoa() throws Exception {
        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY), "PlainController/create", "PlainController/update",
                "PlainController/remove");

        mvc().perform(post("/plain")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
        mvc().perform(put("/plain")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
        mvc().perform(delete("/plain")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
    }

    @Test
    void chiDocAdminCungBiChan() throws Exception {
        login(admin(JwtClaims.TENANT_ACCESS_READ_ONLY));

        mvc().perform(post("/plain")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
        mvc().perform(delete("/plain")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
    }

    @Test
    void chiDocChoGetVaHeadVaVanKiemKhoa() throws Exception {
        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY), "PlainController/read");

        mvc().perform(get("/plain")).andExpect(status().isOk());
        mvc().perform(head("/plain")).andExpect(status().isOk());

        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY));
        mvc().perform(get("/plain")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void chiDocChoOptionsQuaInterceptor() throws Exception {
        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY), "PlainController/create");
        HandlerMethod handler = new HandlerMethod(new PlainController(), PlainController.class.getMethod("create"));
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/plain");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void chiDocChoPhepEndpointAllowedWhenReadOnlyNhungVanKiemKhoa() throws Exception {
        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY), "PlainController/allowed");
        mvc().perform(post("/plain/allowed")).andExpect(status().isOk());

        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY));
        mvc().perform(post("/plain/allowed")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void chiDocChanAuthenticatedOnlyGhiTruCoAllowedWhenReadOnly() throws Exception {
        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY));

        mvc().perform(post("/plain/me")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
        mvc().perform(post("/plain/me/allowed")).andExpect(status().isOk());
    }

    @Test
    void chiDocKhongChanPublicEndpoint() throws Exception {
        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY));

        mvc().perform(post("/plain/public")).andExpect(status().isOk());
    }

    @Test
    void khiVangClaimHoacFullHanhViYNhuCu() throws Exception {
        login(user(null), "PlainController/create");
        mvc().perform(post("/plain")).andExpect(status().isOk());

        login(user(JwtClaims.TENANT_ACCESS_FULL), "PlainController/create");
        mvc().perform(post("/plain")).andExpect(status().isOk());

        login(admin(null));
        mvc().perform(delete("/plain")).andExpect(status().isOk());
    }

    @Test
    void giaTriTenantAccessLaBiChanFailClosed() throws Exception {
        login(admin("WEIRD"));

        mvc().perform(post("/plain")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
    }

    @Test
    void platformAdminKhongTenantLamThayKhongBiChan() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.PLATFORM_ADMIN, true);
        claims.put(JwtClaims.TENANT_ACCESS, JwtClaims.TENANT_ACCESS_READ_ONLY);
        login(claims);

        mvc().perform(post("/plain/platform")).andExpect(status().isOk());
        mvc().perform(post("/plain").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminKhongCoClaimChiDocVanNhuCu() throws Exception {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.PLATFORM_ADMIN, true);
        login(claims);

        mvc().perform(post("/plain").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void chiDocApDungChoTokenStorefrontSvcVaIntegrationKhiCoClaim() throws Exception {
        login(storefrontSvc(JwtClaims.TENANT_ACCESS_READ_ONLY));
        mvc().perform(post("/shop/orders")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
        mvc().perform(get("/shop/items")).andExpect(status().isOk());

        login(integration(JwtClaims.TENANT_ACCESS_READ_ONLY));
        mvc().perform(post("/int/orders")).andExpect(status().isForbidden()).andExpect(code("TENANT_READ_ONLY"));
    }

    @Test
    void tokenStorefrontSvcVaIntegrationKhongClaimGiuNguyenHanhViCu() throws Exception {
        login(storefrontSvc(null));
        mvc().perform(post("/shop/orders")).andExpect(status().isOk());
        mvc().perform(post("/plain")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));

        login(integration(null));
        mvc().perform(post("/int/orders")).andExpect(status().isOk());
        mvc().perform(post("/plain")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void chiDocGetVanDangKyKhoaLanDau() throws Exception {
        login(user(JwtClaims.TENANT_ACCESS_READ_ONLY), "PlainController/read");

        mvc().perform(get("/plain")).andExpect(status().isOk());

        verify(registrar).ensureRegistered(any());
    }

    // ---------- Token tenant-data ----------

    @Test
    void tenantDataQuaEndpointTenantDataAccessKhongDangKyKhoa() throws Exception {
        login(tenantData());

        mvc().perform(get("/data/export")).andExpect(status().isOk());
        mvc().perform(post("/data/purge")).andExpect(status().isOk());

        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void tenantDataKhongCanModuleKhiTenantDaThuHoiModule() throws Exception {
        login(tenantData());

        mvc().perform(get("/data/sales")).andExpect(status().isOk());
    }

    @Test
    void tenantDataThieuTenantBi403TenantRequired() throws Exception {
        Map<String, Object> claims = tenantData();
        claims.remove(JwtClaims.TENANT_ID);
        login(claims);

        mvc().perform(get("/data/export")).andExpect(status().isForbidden()).andExpect(code("TENANT_REQUIRED"));
    }

    @Test
    void tenantDataThieuHoacSaiAudBi403() throws Exception {
        Map<String, Object> claims = tenantData();
        claims.remove(JwtClaims.AUDIENCE);
        login(claims);
        mvc().perform(get("/data/export")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));

        claims.put(JwtClaims.AUDIENCE, List.of(JwtClaims.AUDIENCE_STOREFRONT));
        login(claims);
        mvc().perform(get("/data/export")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void tenantDataKhongDungDuocOEndpointKhac() throws Exception {
        Map<String, Object> claims = tenantData();
        claims.put(JwtClaims.ADMIN, true);
        claims.put(JwtClaims.MODULES, List.of("SALES"));
        login(claims, "PlainController/read", "PlainController/create", "ShopController/items");

        mvc().perform(get("/plain")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc().perform(post("/plain")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc().perform(post("/plain/allowed")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc().perform(post("/plain/me")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc().perform(post("/plain/platform")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc().perform(get("/shop/items")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        mvc().perform(post("/int/orders")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void tenantDataVaoEndpointPublicVanDuocNhuMoiToken() throws Exception {
        login(tenantData());

        mvc().perform(post("/plain/public")).andExpect(status().isOk());
    }

    @Test
    void moiTokenKhacBi403TrenTenantDataAccessKhongLoTenKhoa() throws Exception {
        Map<String, Object> platformAdmin = new HashMap<>();
        platformAdmin.put(JwtClaims.PLATFORM_ADMIN, true);

        List<Map<String, Object>> tokens = List.of(user(null), user(JwtClaims.TENANT_ACCESS_READ_ONLY), admin(null),
                storefrontSvc(null), integration(null), platformAdmin);
        for (Map<String, Object> claims : tokens) {
            login(claims, "DataController/export", "DataController/purge", "DataController/sales");
            mvc().perform(get("/data/export")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("DataController"))));
            mvc().perform(post("/data/purge").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                    .andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
            mvc().perform(get("/data/sales")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
        }
        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void tenantDataAccessThangPublicEndpoint() throws Exception {
        mvc().perform(get("/data/public")).andExpect(status().isUnauthorized());

        login(user(null), "DataController/publicToo");
        mvc().perform(get("/data/public")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));

        login(tenantData());
        mvc().perform(get("/data/public")).andExpect(status().isOk());
    }

    @Test
    void chuaDangNhapVan401() throws Exception {
        mvc().perform(get("/data/export")).andExpect(status().isUnauthorized());
        mvc().perform(post("/plain")).andExpect(status().isUnauthorized());
    }

    @Test
    void resolverTraCoTenantDataAccessVaAllowedWhenReadOnly() throws Exception {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        PermissionKeyResolver.Resolved export = resolver.resolve(
                new HandlerMethod(new DataController(), DataController.class.getMethod("export")));
        assertThat(export.tenantDataAccess()).isTrue();
        assertThat(export.allowedWhenReadOnly()).isFalse();
        assertThat(export.mode()).isEqualTo(PermissionKeyResolver.Mode.PERMISSION);
        assertThat(export.key()).isEqualTo("DataController/export");

        PermissionKeyResolver.Resolved allowed = resolver.resolve(
                new HandlerMethod(new PlainController(), PlainController.class.getMethod("allowed")));
        assertThat(allowed.allowedWhenReadOnly()).isTrue();
        assertThat(allowed.tenantDataAccess()).isFalse();

        PermissionKeyResolver.Resolved plain = resolver.resolve(
                new HandlerMethod(new PlainController(), PlainController.class.getMethod("read")));
        assertThat(plain.tenantDataAccess()).isFalse();
        assertThat(plain.allowedWhenReadOnly()).isFalse();
    }
}
