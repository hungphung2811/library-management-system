package lms.security.permission;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
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

/** ADR-0009: bảng quyết định của nhánh kiểm module trong PermissionInterceptor. */
class RequiresModuleInterceptorTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @RestController
    @RequiresModule(TenantModule.SALES)
    static class SalesController {
        @GetMapping("/sales/orders")
        public String orders() { return "ok"; }

        @AuthenticatedOnly
        @GetMapping("/sales/me")
        public String me() { return "ok"; }

        @PlatformAdminOnly
        @GetMapping("/sales/platform")
        public String platform() { return "ok"; }

        @CrossTenantReadable
        @GetMapping("/sales/cross")
        public String cross() { return "ok"; }

        @PublicEndpoint
        @GetMapping("/sales/public")
        public String publicOne() { return "ok"; }

        /** Method ghi đè class: endpoint này thuộc LENDING. */
        @RequiresModule(TenantModule.LENDING)
        @GetMapping("/sales/lending")
        public String lending() { return "ok"; }
    }

    @RestController
    static class CoreController {
        @GetMapping("/core")
        public String core() { return "ok"; }

        @AuthenticatedOnly
        @GetMapping("/core/me")
        public String me() { return "ok"; }

        @RequiresModule(TenantModule.LENDING)
        @GetMapping("/core/lending")
        public String lending() { return "ok"; }
    }

    private final PermissionKeyRegistrar registrar = mock(PermissionKeyRegistrar.class);

    private MockMvc mvc(boolean legacyFallback) {
        return MockMvcBuilders.standaloneSetup(new SalesController(), new CoreController())
                .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), registrar, "sample", false,
                        TenantScopeBinder.NOOP, legacyFallback))
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

    private static Map<String, Object> tenantWith(Object modules) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaims.TENANT_ID, TENANT.toString());
        claims.put(JwtClaims.MODULES, modules);
        return claims;
    }

    private static ResultMatcher code(String code) {
        return content().string(org.hamcrest.Matchers.containsString("\"code\":\"" + code + "\""));
    }

    @Test
    void coModuleVaCoKhoaDuocQua() throws Exception {
        login(tenantWith(List.of("SALES")), "SalesController/orders");

        mvc(true).perform(get("/sales/orders")).andExpect(status().isOk());
    }

    @Test
    void khongCoModuleBi403ModuleNotEnabledDuCoKhoa() throws Exception {
        login(tenantWith(List.of("LENDING")), "SalesController/orders");

        mvc(true).perform(get("/sales/orders")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void adminCuaTenantKhongBoQuaKiemModule() throws Exception {
        Map<String, Object> claims = tenantWith(List.of("LENDING"));
        claims.put(JwtClaims.ADMIN, true);
        login(claims);

        mvc(true).perform(get("/sales/orders")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void adminCoModuleQuaKhongCanKhoa() throws Exception {
        Map<String, Object> claims = tenantWith(List.of("SALES"));
        claims.put(JwtClaims.ADMIN, true);
        login(claims);

        mvc(true).perform(get("/sales/orders")).andExpect(status().isOk());
    }

    @Test
    void coModuleNhungThieuKhoaVan403Forbidden() throws Exception {
        login(tenantWith(List.of("SALES")));

        mvc(true).perform(get("/sales/orders")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void khoaVanDuocDangKyKhiModuleTat() throws Exception {
        login(tenantWith(List.of("LENDING")));

        mvc(true).perform(get("/sales/orders")).andExpect(status().isForbidden());

        verify(registrar).ensureRegistered(any());
    }

    @Test
    void tokenKhongTenantTrenEndpointCoModuleBi403TenantRequiredDuKhongBatRequireTenant() throws Exception {
        login(Map.of(JwtClaims.ADMIN, true));

        mvc(true).perform(get("/sales/orders")).andExpect(status().isForbidden()).andExpect(code("TENANT_REQUIRED"));

        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void authenticatedOnlyCoGanModuleVanBiKiem() throws Exception {
        login(tenantWith(List.of("LENDING")));

        mvc(true).perform(get("/sales/me")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void authenticatedOnlyCoGanModuleDuModuleThiQuaKhongCanKhoaVaKhongDangKy() throws Exception {
        login(tenantWith(List.of("SALES")));

        mvc(true).perform(get("/sales/me")).andExpect(status().isOk());

        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void authenticatedOnlyCoGanModuleTokenKhongTenantBi403TenantRequired() throws Exception {
        login(Map.of(JwtClaims.PLATFORM_ADMIN, true));

        mvc(true).perform(get("/sales/me")).andExpect(status().isForbidden()).andExpect(code("TENANT_REQUIRED"));
    }

    @Test
    void authenticatedOnlyChungKhongGanModuleKhongBiChan() throws Exception {
        login(Map.of(JwtClaims.USERNAME, "u"));

        mvc(true).perform(get("/core/me")).andExpect(status().isOk());
    }

    @Test
    void platformAdminOnlyTrenClassCoModuleKhongBiChan() throws Exception {
        login(Map.of(JwtClaims.PLATFORM_ADMIN, true));

        mvc(true).perform(get("/sales/platform")).andExpect(status().isOk());
    }

    @Test
    void nguoiKhongPhaiPlatformAdminGoiPlatformAdminOnlyVan403Forbidden() throws Exception {
        login(tenantWith(List.of("SALES")));

        mvc(true).perform(get("/sales/platform")).andExpect(status().isForbidden()).andExpect(code("FORBIDDEN"));
    }

    @Test
    void docXuyenTenantCuaPlatformAdminKhongBiChanModule() throws Exception {
        login(Map.of(JwtClaims.PLATFORM_ADMIN, true));

        mvc(true).perform(get("/sales/cross")).andExpect(status().isOk());
    }

    @Test
    void platformAdminDaSwitchTenantChiuModuleCuaTenant() throws Exception {
        Map<String, Object> claims = tenantWith(List.of("LENDING"));
        claims.put(JwtClaims.PLATFORM_ADMIN, true);
        login(claims);

        mvc(true).perform(get("/sales/cross")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void publicEndpointKhongBiKiemModule() throws Exception {
        mvc(true).perform(get("/sales/public")).andExpect(status().isOk());
    }

    @Test
    void chuaDangNhapTrenEndpointCoModuleVan401() throws Exception {
        mvc(true).perform(get("/sales/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void methodGhiDeModuleCuaClass() throws Exception {
        login(tenantWith(List.of("SALES")), "SalesController/lending");
        mvc(true).perform(get("/sales/lending")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));

        login(tenantWith(List.of("LENDING")), "SalesController/lending");
        mvc(true).perform(get("/sales/lending")).andExpect(status().isOk());
    }

    @Test
    void tokenCuThieuClaimModulesCoiLaLendingKhiBatCo() throws Exception {
        login(Map.of(JwtClaims.TENANT_ID, TENANT.toString()), "CoreController/lending", "SalesController/orders");

        mvc(true).perform(get("/core/lending")).andExpect(status().isOk());
        mvc(true).perform(get("/sales/orders")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void tokenCuThieuClaimModulesBi403KhiTatCo() throws Exception {
        login(Map.of(JwtClaims.TENANT_ID, TENANT.toString()), "CoreController/lending");

        mvc(false).perform(get("/core/lending")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void claimModulesRongLaKhongCoModuleNaoKeCaKhiBatCoLegacy() throws Exception {
        login(tenantWith(List.of()), "CoreController/lending");

        mvc(true).perform(get("/core/lending")).andExpect(status().isForbidden()).andExpect(code("MODULE_NOT_ENABLED"));
    }

    @Test
    void claimModulesSaiKieuHoacGiaTriLaBiBoQua() throws Exception {
        login(tenantWith("LENDING"), "CoreController/lending");
        mvc(true).perform(get("/core/lending")).andExpect(status().isForbidden());

        login(tenantWith(List.of("KHONG_TON_TAI", "sales")), "SalesController/orders");
        mvc(true).perform(get("/sales/orders")).andExpect(status().isForbidden());
    }

    @Test
    void endpointKhongGanModuleGiuNguyenHanhViCu() throws Exception {
        login(tenantWith(List.of()), "CoreController/core");

        mvc(true).perform(get("/core")).andExpect(status().isOk());
    }

    @Test
    void resolverTraRequiredModuleMethodThangClass() throws Exception {
        PermissionKeyResolver resolver = new PermissionKeyResolver();
        SalesController sales = new SalesController();
        assertThat(resolver.resolve(new HandlerMethod(sales, SalesController.class.getMethod("orders")))
                .requiredModule()).isEqualTo(TenantModule.SALES);
        assertThat(resolver.resolve(new HandlerMethod(sales, SalesController.class.getMethod("lending")))
                .requiredModule()).isEqualTo(TenantModule.LENDING);
        assertThat(resolver.resolve(new HandlerMethod(new CoreController(), CoreController.class.getMethod("core")))
                .requiredModule()).isNull();
    }
}
