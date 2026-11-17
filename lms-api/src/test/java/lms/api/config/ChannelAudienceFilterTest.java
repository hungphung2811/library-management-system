package lms.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lms.api.identity.client.AccessClient;
import lms.api.identity.client.AuthClient;
import lms.api.identity.client.DepartmentClient;
import lms.api.identity.client.UserClient;
import lms.api.identity.web.UserController;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Ranh giới kênh token ở lms-api (A1): token aud=lms-storefront bị 401, token không aud vẫn qua. */
class ChannelAudienceFilterTest {

    private static Jwt token(List<String> audience) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("tenantId", "x");
        if (audience != null) b.audience(audience);
        return b.build();
    }

    private static int run(ChannelAudienceFilter filter, List<String> audience, FilterChain chain) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token(audience)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/users/me"), response, chain);
        return response.getStatus();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenKenhStorefrontBiTuChoi401VaKhongDiTiep() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        int status = run(new ChannelAudienceFilter(false), List.of("lms-storefront"), chain);
        assertThat(status).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void audChuaStorefrontCungVoiGiaTriKhacVanBiTuChoi() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        assertThat(run(new ChannelAudienceFilter(false), List.of("other", "lms-storefront"), chain)).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    void tokenKhongAudVanDuocQuaTrongGiaiDoanChuyenTiep() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        int status = run(new ChannelAudienceFilter(false), null, chain);
        assertThat(status).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tokenAudStaffDuocQua() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        assertThat(run(new ChannelAudienceFilter(false), List.of("lms-staff"), chain)).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void khiBatCoYeuCauAudStaffThiTokenKhongAudBiTuChoi() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        assertThat(run(new ChannelAudienceFilter(true), null, chain)).isEqualTo(401);
        verifyNoInteractions(chain);
        FilterChain ok = mock(FilterChain.class);
        assertThat(run(new ChannelAudienceFilter(true), List.of("lms-staff"), ok)).isEqualTo(200);
        verify(ok).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void khongCoXacThucThiKhongLamGi() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ChannelAudienceFilter(true).doFilter(new MockHttpServletRequest("GET", "/api/auth/login"), response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /** Nối dây thật trong SecurityConfiguration (mặc định: token không aud vẫn qua). */
    @Nested
    @WebMvcTest(UserController.class)
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    class Wiring {
        @Autowired MockMvc mvc;
        @MockitoBean UserClient users;
        @MockitoBean AuthClient auth;
        @MockitoBean AccessClient access;
        @MockitoBean DepartmentClient departments;
        @MockitoBean JwtDecoder jwtDecoder;

        @Test
        void storefrontBi401TokenThuongVaTokenKhongAudQua() throws Exception {
            mvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.audience(List.of("lms-storefront")))))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.claims(c -> c.putAll(Map.of("tenantId", "x"))))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.audience(List.of("lms-staff")))))
                    .andExpect(status().isOk());
        }
    }

    /** Hết giai đoạn chuyển tiếp: bật cờ thì token không aud bị từ chối. */
    @Nested
    @WebMvcTest(UserController.class)
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    @TestPropertySource(properties = "lms.api.security.require-staff-audience=true")
    class WiringRequireStaff {
        @Autowired MockMvc mvc;
        @MockitoBean UserClient users;
        @MockitoBean AuthClient auth;
        @MockitoBean AccessClient access;
        @MockitoBean DepartmentClient departments;
        @MockitoBean JwtDecoder jwtDecoder;

        @Test
        void chiNhanTokenAudStaff() throws Exception {
            mvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.claims(c -> c.putAll(Map.of("tenantId", "x"))))))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.audience(List.of("lms-staff")))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.audience(List.of("lms-storefront")))))
                    .andExpect(status().isUnauthorized());
        }
    }
}
