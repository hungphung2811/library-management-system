package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import lms.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class PermissionInterceptorTest {

    @RestController
    static class SampleController {

        @GetMapping("/auto")
        public String auto() { return "ok"; }

        @RequirePermission("BOOK_VIEW")
        @GetMapping("/custom/{id}")
        public String custom() { return "ok"; }

        @AuthenticatedOnly
        @GetMapping("/me")
        public String me() { return "ok"; }

        @PublicEndpoint
        @GetMapping("/public")
        public String open() { return "ok"; }
    }

    private final PermissionKeyRegistrar registrar = mock(PermissionKeyRegistrar.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SampleController())
            .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), registrar, "sample-service"))
            .build();

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void loginWith(boolean admin, String... authorities) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (admin) jwt.claim(JwtClaims.ADMIN, true);
        List<GrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), granted));
    }

    @Test
    void khoaTuSinhLaTenControllerSlashTenPhuongThucVaBiTuChoiKhiThieuQuyen() throws Exception {
        loginWith(false);

        mvc.perform(get("/auto")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SampleController/auto")));

        ArgumentCaptor<EndpointDescriptor> descriptor = ArgumentCaptor.forClass(EndpointDescriptor.class);
        verify(registrar).ensureRegistered(descriptor.capture());
        assertThat(descriptor.getValue().key()).isEqualTo("SampleController/auto");
        assertThat(descriptor.getValue().source()).isEqualTo("AUTO");
        assertThat(descriptor.getValue().service()).isEqualTo("sample-service");
        assertThat(descriptor.getValue().httpMethod()).isEqualTo("GET");
        assertThat(descriptor.getValue().pathPattern()).isEqualTo("/auto");
    }

    @Test
    void coKhoaTrongTokenThiDuocQua() throws Exception {
        loginWith(false, "SampleController/auto");

        mvc.perform(get("/auto")).andExpect(status().isOk());
    }

    @Test
    void customKeyDuocDungThayChoKhoaTuSinhVaGhiNhanLaCustom() throws Exception {
        loginWith(false, "BOOK_VIEW");

        mvc.perform(get("/custom/42")).andExpect(status().isOk());

        ArgumentCaptor<EndpointDescriptor> descriptor = ArgumentCaptor.forClass(EndpointDescriptor.class);
        verify(registrar).ensureRegistered(descriptor.capture());
        assertThat(descriptor.getValue().key()).isEqualTo("BOOK_VIEW");
        assertThat(descriptor.getValue().source()).isEqualTo("CUSTOM");
        assertThat(descriptor.getValue().pathPattern()).isEqualTo("/custom/{id}");
    }

    @Test
    void khoaTuSinhKhongThayTheDuocCustomKey() throws Exception {
        loginWith(false, "SampleController/custom");

        mvc.perform(get("/custom/42")).andExpect(status().isForbidden());
    }

    @Test
    void adminDuocBoQuaKiemTraNhungKhoaVanDuocDangKy() throws Exception {
        loginWith(true);

        mvc.perform(get("/auto")).andExpect(status().isOk());

        verify(registrar).ensureRegistered(any());
    }

    @Test
    void chiCanDangNhapThiKhongCanQuyenVaKhongDangKyKhoa() throws Exception {
        loginWith(false);

        mvc.perform(get("/me")).andExpect(status().isOk());

        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void endpointCongKhaiBoQuaMoiKiemTra() throws Exception {
        mvc.perform(get("/public")).andExpect(status().isOk());

        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void chuaDangNhapTrenEndpointCanQuyenBi401() throws Exception {
        mvc.perform(get("/auto")).andExpect(status().isUnauthorized());

        verify(registrar, never()).ensureRegistered(any());
    }

    @RestController
    static class PlatformController {

        @PlatformAdminOnly
        @GetMapping("/platform/tenants")
        public String tenants() { return "ok"; }
    }

    private final MockMvc platformMvc = MockMvcBuilders.standaloneSetup(new SampleController(), new PlatformController())
            .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), registrar, "sample-service"))
            .build();

    private final MockMvc tenantMvc = MockMvcBuilders.standaloneSetup(new SampleController(), new PlatformController())
            .addInterceptors(new PermissionInterceptor(new PermissionKeyResolver(), registrar, "sample-service", true))
            .build();

    private void loginWithClaims(java.util.Map<String, Object> claims, String... authorities) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(jwt::claim);
        List<GrantedAuthority> granted = java.util.Arrays.stream(authorities)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), granted));
    }

    @Test
    void platformAdminOnlyChoQuaKhiCoClaimPlatformAdmin() throws Exception {
        loginWithClaims(java.util.Map.of(JwtClaims.PLATFORM_ADMIN, true));

        platformMvc.perform(get("/platform/tenants")).andExpect(status().isOk());
        verify(registrar, never()).ensureRegistered(any());
    }

    @Test
    void platformAdminOnlyBi403KhiThieuClaim() throws Exception {
        loginWith(false);

        platformMvc.perform(get("/platform/tenants")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"FORBIDDEN\"")));
    }

    @Test
    void platformAdminOnlyChuaDangNhapBi401() throws Exception {
        platformMvc.perform(get("/platform/tenants")).andExpect(status().isUnauthorized());
    }

    @Test
    void claimPlatformAdminKhongThayTheQuyenEndpointThuong() throws Exception {
        loginWithClaims(java.util.Map.of(JwtClaims.PLATFORM_ADMIN, true));

        platformMvc.perform(get("/auto")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"FORBIDDEN\"")));
    }

    @Test
    void batRequireTenantThieuTenantIdBi403TenantRequired() throws Exception {
        loginWith(true);

        tenantMvc.perform(get("/auto")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"TENANT_REQUIRED\"")));
    }

    @Test
    void batRequireTenantCoTenantIdThiDuocQua() throws Exception {
        loginWithClaims(java.util.Map.of(JwtClaims.ADMIN, true, JwtClaims.TENANT_ID, java.util.UUID.randomUUID().toString()));

        tenantMvc.perform(get("/auto")).andExpect(status().isOk());
    }

    @Test
    void batRequireTenantKhongChanEndpointCongKhaiVaChiCanDangNhapVaPlatformAdmin() throws Exception {
        tenantMvc.perform(get("/public")).andExpect(status().isOk());

        loginWith(false);
        tenantMvc.perform(get("/me")).andExpect(status().isOk());

        loginWithClaims(java.util.Map.of(JwtClaims.PLATFORM_ADMIN, true));
        tenantMvc.perform(get("/platform/tenants")).andExpect(status().isOk());
    }

    @Test
    void tatRequireTenantThiThieuTenantIdKhongBiChan() throws Exception {
        loginWith(true);

        mvc.perform(get("/auto")).andExpect(status().isOk());
    }

    @Test
    void dangKyLoiKhongLamHongQuyetDinh() throws Exception {
        doThrow(new IllegalStateException("identity chết")).when(registrar).ensureRegistered(any());
        loginWith(false, "SampleController/auto");

        mvc.perform(get("/auto")).andExpect(status().isOk());
    }
}
