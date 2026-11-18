package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ma trận ADR-0016: platform admin hành động trong tenant đích qua header X-Tenant-Id (đọc và ghi). */
class ActAsTenantInterceptorTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @RestController
    static class ItemController {

        @GetMapping("/plain")
        public String plain() { return "ok"; }

        @PostMapping("/items")
        public String create() { return "ok"; }

        @DeleteMapping("/items/1")
        public String remove() { return "ok"; }

        @RequiresModule(TenantModule.SALES)
        @PostMapping("/sales/orders")
        public String order() { return "ok"; }

        @PlatformAdminOnly
        @PostMapping("/platform/tenants")
        public String platform() { return "ok"; }

        /** Endpoint chung chỉ cần đăng nhập, không thuộc module nào (như GET /menus/me). */
        @AuthenticatedOnly
        @GetMapping("/me")
        public String me() { return "ok"; }
    }


    static class RecordingBinder implements TenantScopeBinder {
        final List<String> calls = new ArrayList<>();

        @Override
        public void bindAll() { calls.add("ALL"); }

        @Override
        public void bindSingle(UUID tenantId) { calls.add("SINGLE:" + tenantId); }
    }

    private final RecordingBinder binder = new RecordingBinder();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ItemController())
            .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), mock(PermissionKeyRegistrar.class),
                    "sample", true, binder))
            .build();

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(Map<String, Object> claims) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(jwt::claim);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), List.of()));
    }

    private static Map<String, Object> platformAdmin() {
        return Map.of(JwtClaims.PLATFORM_ADMIN, true);
    }

    @Test
    void platformAdminCoHeaderDocDuocEndpointThuong() throws Exception {
        login(platformAdmin());

        mvc.perform(get("/plain").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk());

        assertThat(binder.calls).containsExactly("SINGLE:" + TENANT);
    }

    @Test
    void platformAdminCoHeaderDatThuocTinhDeClientLienServiceChuyenTiep() throws Exception {
        login(platformAdmin());

        var request = mvc.perform(get("/plain").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andReturn().getRequest();
        assertThat(request.getAttribute(ActAsTenant.ATTRIBUTE)).isEqualTo(TENANT.toString());

        login(Map.of(JwtClaims.ADMIN, true, JwtClaims.TENANT_ID, TENANT.toString()));
        var other = mvc.perform(get("/plain").header(PermissionInterceptor.TENANT_HEADER, OTHER.toString()))
                .andReturn().getRequest();
        assertThat(other.getAttribute(ActAsTenant.ATTRIBUTE)).isNull();
    }

    @Test
    void platformAdminCoHeaderGhiDuocPostVaDelete() throws Exception {
        login(platformAdmin());

        mvc.perform(post("/items").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk());
        mvc.perform(delete("/items/1").header(PermissionInterceptor.TENANT_HEADER, OTHER.toString()))
                .andExpect(status().isOk());

        assertThat(binder.calls).containsExactly("SINGLE:" + TENANT, "SINGLE:" + OTHER);
    }

    @Test
    void platformAdminCoHeaderBoQuaKiemModule() throws Exception {
        login(platformAdmin());

        mvc.perform(post("/sales/orders").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk());

        assertThat(binder.calls).containsExactly("SINGLE:" + TENANT);
    }

    @Test
    void platformAdminKhongHeaderVan403TenantRequired() throws Exception {
        login(platformAdmin());

        mvc.perform(post("/items"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("TENANT_REQUIRED")));

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void platformAdminHeaderSaiDinhDangBi400() throws Exception {
        login(platformAdmin());

        mvc.perform(post("/items").header(PermissionInterceptor.TENANT_HEADER, "khong-phai-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("INVALID_TENANT_ID")));

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void adminCuaTenantGuiHeaderBiBoQuaKhongDoiTenant() throws Exception {
        login(Map.of(JwtClaims.ADMIN, true, JwtClaims.TENANT_ID, TENANT.toString()));

        mvc.perform(post("/items").header(PermissionInterceptor.TENANT_HEADER, OTHER.toString()))
                .andExpect(status().isOk());

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void nguoiDungThuongKhongTenantGuiHeaderKhongDuocQuyen() throws Exception {
        login(Map.of());

        mvc.perform(post("/items").header(PermissionInterceptor.TENANT_HEADER, OTHER.toString()))
                .andExpect(status().isForbidden());

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void endpointPlatformAdminOnlyKhongBindTenant() throws Exception {
        login(platformAdmin());

        mvc.perform(post("/platform/tenants").header(PermissionInterceptor.TENANT_HEADER, OTHER.toString()))
                .andExpect(status().isOk());

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void endpointChiCanDangNhapBindTenantKhiPlatformAdminGuiHeaderVaDatThuocTinh() throws Exception {
        login(platformAdmin());

        var request = mvc.perform(get("/me").header(PermissionInterceptor.TENANT_HEADER, TENANT.toString()))
                .andExpect(status().isOk()).andReturn().getRequest();

        assertThat(binder.calls).containsExactly("SINGLE:" + TENANT);
        assertThat(request.getAttribute(ActAsTenant.ATTRIBUTE)).isEqualTo(TENANT.toString());
    }

    @Test
    void endpointChiCanDangNhapKhongHeaderKhongBindTenant() throws Exception {
        login(platformAdmin());

        var request = mvc.perform(get("/me")).andExpect(status().isOk()).andReturn().getRequest();

        assertThat(binder.calls).isEmpty();
        assertThat(request.getAttribute(ActAsTenant.ATTRIBUTE)).isNull();
    }

    @Test
    void endpointChiCanDangNhapAdminTenantGuiHeaderBiBoQua() throws Exception {
        login(Map.of(JwtClaims.ADMIN, true, JwtClaims.TENANT_ID, TENANT.toString()));

        var request = mvc.perform(get("/me").header(PermissionInterceptor.TENANT_HEADER, OTHER.toString()))
                .andExpect(status().isOk()).andReturn().getRequest();

        assertThat(binder.calls).isEmpty();
        assertThat(request.getAttribute(ActAsTenant.ATTRIBUTE)).isNull();
    }

    @Test
    void endpointChiCanDangNhapHeaderSaiDinhDangTuPlatformAdminBi400() throws Exception {
        login(platformAdmin());

        mvc.perform(get("/me").header(PermissionInterceptor.TENANT_HEADER, "khong-phai-uuid"))
                .andExpect(status().isBadRequest());

        assertThat(binder.calls).isEmpty();
    }
}
