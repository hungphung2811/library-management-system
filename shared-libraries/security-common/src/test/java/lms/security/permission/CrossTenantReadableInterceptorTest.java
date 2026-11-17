package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ma trận ADR-0008: nhánh đọc xuyên tenant của PermissionInterceptor. */
class CrossTenantReadableInterceptorTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @RestController
    static class ItemController {

        @CrossTenantReadable
        @GetMapping("/items")
        public String list() { return "ok"; }

        @CrossTenantReadable
        @PostMapping("/items")
        public String create() { return "ok"; }

        @GetMapping("/plain")
        public String plain() { return "ok"; }
    }

    /** Ghi lại lời gọi binder. */
    static class RecordingBinder implements TenantScopeBinder {
        final List<String> calls = new ArrayList<>();

        @Override
        public void bindAll() { calls.add("ALL"); }

        @Override
        public void bindSingle(UUID tenantId) { calls.add("SINGLE:" + tenantId); }
    }

    private final PermissionKeyRegistrar registrar = mock(PermissionKeyRegistrar.class);
    private final RecordingBinder binder = new RecordingBinder();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ItemController())
            .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), registrar, "sample", true, binder))
            .build();

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

    private static Map<String, Object> platformAdmin() {
        return Map.of(JwtClaims.PLATFORM_ADMIN, true);
    }

    @Test
    void platformAdminKhongTenantKhongQueryBindAllVaKhongCanKhoa() throws Exception {
        login(platformAdmin());

        mvc.perform(get("/items")).andExpect(status().isOk());

        assertThat(binder.calls).containsExactly("ALL");
        verify(registrar).ensureRegistered(any());
    }

    @Test
    void platformAdminKhongTenantQueryRongCungBindAll() throws Exception {
        login(platformAdmin());

        mvc.perform(get("/items").param("tenantId", " ")).andExpect(status().isOk());

        assertThat(binder.calls).containsExactly("ALL");
    }

    @Test
    void platformAdminKhongTenantCoQueryHopLeBindSingle() throws Exception {
        login(platformAdmin());

        mvc.perform(get("/items").param("tenantId", OTHER.toString())).andExpect(status().isOk());

        assertThat(binder.calls).containsExactly("SINGLE:" + OTHER);
    }

    @Test
    void platformAdminQuerySaiDinhDangBi400VaKhongBind() throws Exception {
        login(platformAdmin());

        mvc.perform(get("/items").param("tenantId", "khong-phai-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"INVALID_TENANT_ID\"")));

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void adminCuaTenantGuiTenantIdBiBoQuaVaVanKiemKhoaTheoLuongCu() throws Exception {
        login(Map.of(JwtClaims.ADMIN, true, JwtClaims.TENANT_ID, TENANT.toString()));

        mvc.perform(get("/items").param("tenantId", OTHER.toString())).andExpect(status().isOk());
        mvc.perform(get("/items").param("tenantId", "sai-dinh-dang")).andExpect(status().isOk());

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void nguoiDungThuongGuiTenantIdBiBoQuaVaThieuKhoaVan403() throws Exception {
        login(Map.of(JwtClaims.TENANT_ID, TENANT.toString()));

        mvc.perform(get("/items").param("tenantId", OTHER.toString()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"FORBIDDEN\"")));

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void nguoiDungThuongCoKhoaDuocQuaVaKhongBind() throws Exception {
        login(Map.of(JwtClaims.TENANT_ID, TENANT.toString()), "ItemController/list");

        mvc.perform(get("/items").param("tenantId", OTHER.toString())).andExpect(status().isOk());

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void platformAdminDaSwitchTenantCoiNhuNguoiDungCuaTenant() throws Exception {
        login(Map.of(JwtClaims.PLATFORM_ADMIN, true, JwtClaims.TENANT_ID, TENANT.toString()));

        mvc.perform(get("/items").param("tenantId", OTHER.toString())).andExpect(status().isForbidden());

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void platformAdminGoiPostDuCoAnnotationKhongMoAllVaVan403TenantRequired() throws Exception {
        login(platformAdmin());

        mvc.perform(post("/items")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"TENANT_REQUIRED\"")));

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void tokenKhongTenantKhongPhaiPlatformAdminVan403TenantRequired() throws Exception {
        login(Map.of(JwtClaims.ADMIN, true));

        mvc.perform(get("/items")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"TENANT_REQUIRED\"")));
        mvc.perform(get("/items").param("tenantId", TENANT.toString())).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"TENANT_REQUIRED\"")));

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void endpointKhongCoAnnotationPlatformAdminVan403TenantRequired() throws Exception {
        login(platformAdmin());

        mvc.perform(get("/plain")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"TENANT_REQUIRED\"")));

        assertThat(binder.calls).isEmpty();
    }

    @Test
    void chuaDangNhapVan401() throws Exception {
        mvc.perform(get("/items")).andExpect(status().isUnauthorized());

        assertThat(binder.calls).isEmpty();
        verify(registrar, never()).ensureRegistered(any());
    }
}
