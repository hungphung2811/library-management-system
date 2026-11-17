package lms.identity.auth.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ExchangeExternalTicketUseCase;
import lms.identity.auth.application.ExternalIdentity;
import lms.identity.auth.application.ExternalLoginResponse;
import lms.identity.auth.application.ExternalSignInUseCase;
import lms.identity.auth.application.LinkExternalLoginUseCase;
import lms.identity.auth.application.ListExternalLoginsUseCase;
import lms.identity.auth.application.UnlinkExternalLoginUseCase;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.infrastructure.config.ExternalAuthProperties;
import lms.identity.auth.infrastructure.config.ExternalAuthProperties.Provider;
import lms.identity.auth.infrastructure.security.ExternalProviderRegistry;
import lms.identity.auth.infrastructure.security.ExternalProviderRegistry.ProviderInfo;
import lms.identity.auth.infrastructure.security.ExternalState;
import lms.identity.auth.infrastructure.security.OidcClient;
import lms.identity.auth.infrastructure.security.ReturnUrlValidator;
import lms.identity.auth.infrastructure.security.StateCookieCodec;
import lms.identity.auth.infrastructure.web.ExternalAuthController;
import lms.identity.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(ExternalAuthController.class)
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, ExternalAuthControllerSecurityTest.Beans.class})
@TestPropertySource(properties = {"lms.security.permission.enabled=true", "lms.security.permission.require-tenant=true"})
class ExternalAuthControllerSecurityTest {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String RETURN_URL = "http://localhost:3000/login";
    static final String AUTH_URL = "https://idp.example/authorize?x=1";

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        ExternalAuthProperties externalAuthProperties() {
            return new ExternalAuthProperties(); // publicBaseUrl mặc định, cookieSecure=false
        }

        @Bean
        ReturnUrlValidator returnUrlValidator() {
            return new ReturnUrlValidator(List.of("http://localhost:3000"));
        }

        @Bean
        StateCookieCodec stateCookieCodec() {
            return new StateCookieCodec(new ObjectMapper(), "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired StateCookieCodec codec;

    @MockitoBean ExternalProviderRegistry registry;
    @MockitoBean OidcClient oidc;
    @MockitoBean ExternalSignInUseCase signIn;
    @MockitoBean LinkExternalLoginUseCase link;
    @MockitoBean UnlinkExternalLoginUseCase unlink;
    @MockitoBean ListExternalLoginsUseCase list;
    @MockitoBean ExchangeExternalTicketUseCase exchange;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private final UUID userId = UUID.randomUUID();
    private final Provider provider = new Provider();

    @BeforeEach
    void setUp() {
        when(registry.find("google")).thenReturn(Optional.of(provider));
        when(oidc.authorizationUrl(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(AUTH_URL);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
        return jwt().jwt(j -> j.subject(userId.toString()).claim("tenantId", UUID.randomUUID().toString()));
    }

    /** Gọi start để lấy cookie trạng thái thật rồi giải mã lấy state. */
    private record Begun(String cookieValue, ExternalState state) {
    }

    private Begun begin() throws Exception {
        MvcResult r = mvc.perform(get("/internal/auth/external/google/start").param("returnUrl", RETURN_URL))
                .andExpect(status().isFound()).andReturn();
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        String value = setCookie.substring("lms_ext=".length(), setCookie.indexOf(';'));
        return new Begun(value, codec.decode(value, NOW).orElseThrow());
    }

    // ---------- providers ----------

    @Test
    void danhSachNhaCungCapLaCongKhai() throws Exception {
        when(registry.list()).thenReturn(List.of(new ProviderInfo("google", "Google")));

        mvc.perform(get("/internal/auth/external/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("google"))
                .andExpect(jsonPath("$[0].displayName").value("Google"));
    }

    // ---------- start ----------

    @Test
    void startCongKhaiChuyenHuongKemCookieHttpOnlyVaKhongLuuCache() throws Exception {
        mvc.perform(get("/internal/auth/external/google/start").param("returnUrl", RETURN_URL))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", AUTH_URL))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("lms_ext="),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"),
                        org.hamcrest.Matchers.containsString("Path=/api/auth"))));
    }

    @Test
    void startTenNhaCungCapKhongPhanBietHoaThuong() throws Exception {
        mvc.perform(get("/internal/auth/external/GOOGLE/start").param("returnUrl", RETURN_URL))
                .andExpect(status().isFound());
        verify(registry).find("google");
    }

    @Test
    void startReturnUrlNgoaiOriginChoPhepTra400() throws Exception {
        mvc.perform(get("/internal/auth/external/google/start").param("returnUrl", "http://evil.example/x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETURN_URL_NOT_ALLOWED"));
        mvc.perform(get("/internal/auth/external/google/start").param("returnUrl", "javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETURN_URL_NOT_ALLOWED"));
        // fragment hoặc userinfo bị cấm (chống open redirect)
        mvc.perform(get("/internal/auth/external/google/start").param("returnUrl", RETURN_URL + "#a"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/internal/auth/external/google/start").param("returnUrl", "http://u:p@localhost:3000/x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startThieuReturnUrlTra400() throws Exception {
        mvc.perform(get("/internal/auth/external/google/start")).andExpect(status().isBadRequest());
    }

    @Test
    void startNhaCungCapChuaCauHinhTra404() throws Exception {
        when(registry.find("facebook")).thenReturn(Optional.empty());

        mvc.perform(get("/internal/auth/external/facebook/start").param("returnUrl", RETURN_URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROVIDER_NOT_CONFIGURED"));
    }

    // ---------- callback ----------

    @Test
    void callbackKhongCoCookieTra400InvalidStateVaXoaCookie() throws Exception {
        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        verifyNoInteractions(signIn, link);
    }

    @Test
    void callbackCookieGiaMaoHoacSaiNhaCungCapTra400() throws Exception {
        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c").param("state", "s")
                        .cookie(new Cookie("lms_ext", "rac.rac")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        Begun b = begin();
        mvc.perform(get("/internal/auth/external/other/callback").param("code", "c").param("state", b.state().state())
                        .cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        verify(signIn, never()).execute(any());
    }

    @Test
    void callbackThanhCongDangNhapChuyenVeReturnUrlKemMaODauFragment() throws Exception {
        Begun b = begin();
        when(oidc.exchange(eq("google"), eq(provider), eq("code1"), eq(b.state().codeVerifier()),
                eq(b.state().nonce()), anyString()))
                .thenReturn(new ExternalIdentity("google", "sub1", "a@x.vn", true));
        when(signIn.execute(any())).thenReturn("ticket-xyz");

        mvc.perform(get("/internal/auth/external/google/callback").param("code", "code1")
                        .param("state", b.state().state()).cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", RETURN_URL + "#externalCode=ticket-xyz"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void callbackNguoiDungTuChoiChuyenVeKemAuthError() throws Exception {
        Begun b = begin();

        mvc.perform(get("/internal/auth/external/google/callback").param("error", "access_denied")
                        .cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", RETURN_URL + "?authError=EXTERNAL_DENIED"));
        verify(signIn, never()).execute(any());
    }

    @Test
    void callbackStateKhongKhopHoacThieuCodeChuyenVeInvalidState() throws Exception {
        Begun b = begin();

        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c").param("state", "state-khac")
                        .cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", RETURN_URL + "?authError=INVALID_STATE"));
        mvc.perform(get("/internal/auth/external/google/callback").param("state", b.state().state())
                        .cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", RETURN_URL + "?authError=INVALID_STATE"));
        verifyNoInteractions(link);
        verify(signIn, never()).execute(any());
    }

    @Test
    void callbackLoiNhaCungCapChuyenVeKemMaLyDo() throws Exception {
        Begun b = begin();
        when(oidc.exchange(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ExternalAuthException(Reason.EXCHANGE_FAILED, "đổi mã thất bại"));

        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c")
                        .param("state", b.state().state()).cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", RETURN_URL + "?authError=EXCHANGE_FAILED"));
    }

    @Test
    void callbackTaiKhoanBiKhoaHoacVoHieuChuyenVeAccountUnavailable() throws Exception {
        Begun b = begin();
        when(oidc.exchange(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ExternalIdentity("google", "sub1", "a@x.vn", true));
        when(signIn.execute(any())).thenThrow(new AccountLockedException(NOW.plusSeconds(60)));

        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c")
                        .param("state", b.state().state()).cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(header().string("Location", RETURN_URL + "?authError=ACCOUNT_UNAVAILABLE"));

        org.mockito.Mockito.doThrow(new AccountDisabledException()).when(signIn).execute(any());
        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c")
                        .param("state", b.state().state()).cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(header().string("Location", RETURN_URL + "?authError=ACCOUNT_UNAVAILABLE"));
    }

    @Test
    void callbackNhaCungCapBiGoCauHinhGiuaChungChuyenVeProviderNotConfigured() throws Exception {
        Begun b = begin();
        when(registry.find("google")).thenReturn(Optional.empty());

        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c")
                        .param("state", b.state().state()).cookie(new Cookie("lms_ext", b.cookieValue())))
                .andExpect(header().string("Location", RETURN_URL + "?authError=PROVIDER_NOT_CONFIGURED"));
    }

    @Test
    void callbackCheDoLienKetGanTaiKhoanDungNguoiDungVaBaoLinked() throws Exception {
        // trạng thái LINK được tạo qua endpoint link (cần đăng nhập)
        MvcResult r = mvc.perform(get("/internal/auth/me/external/google/link").param("returnUrl", RETURN_URL)
                        .with(user()))
                .andExpect(status().isOk()).andReturn();
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        String value = setCookie.substring("lms_ext=".length(), setCookie.indexOf(';'));
        ExternalState s = codec.decode(value, NOW).orElseThrow();
        assertEquals(ExternalState.Mode.LINK, s.mode());
        assertEquals(userId, s.linkUserId());
        ExternalIdentity identity = new ExternalIdentity("google", "sub1", "a@x.vn", true);
        when(oidc.exchange(anyString(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(identity);

        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c").param("state", s.state())
                        .cookie(new Cookie("lms_ext", value)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", RETURN_URL + "?linked=google"));
        verify(link).execute(userId, identity);
        verify(signIn, never()).execute(any());
    }

    @Test
    void callbackLienKetTrungChuyenVeAlreadyLinked() throws Exception {
        MvcResult r = mvc.perform(get("/internal/auth/me/external/google/link").param("returnUrl", RETURN_URL)
                        .with(user())).andReturn();
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        String value = setCookie.substring("lms_ext=".length(), setCookie.indexOf(';'));
        ExternalState s = codec.decode(value, NOW).orElseThrow();
        when(oidc.exchange(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ExternalIdentity("google", "sub1", "a@x.vn", true));
        org.mockito.Mockito.doThrow(new ExternalAuthException(Reason.ALREADY_LINKED, "đã liên kết"))
                .when(link).execute(any(), any());

        mvc.perform(get("/internal/auth/external/google/callback").param("code", "c").param("state", s.state())
                        .cookie(new Cookie("lms_ext", value)))
                .andExpect(header().string("Location", RETURN_URL + "?authError=ALREADY_LINKED"));
    }

    // ---------- exchange ----------

    @Test
    void doiMaLaCongKhaiTraTokenKhongLuuCache() throws Exception {
        when(exchange.execute(eq("ticket-xyz"), any())).thenReturn(TokenResponse.bearer("a", "r", 900));

        mvc.perform(post("/internal/auth/external/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ticket-xyz\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("a"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void doiMaThieuHoacRongCodeTra400() throws Exception {
        mvc.perform(post("/internal/auth/external/exchange").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/internal/auth/external/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(exchange);
    }

    @Test
    void doiMaSaiHoacDaDungTra401InvalidTicket() throws Exception {
        when(exchange.execute(anyString(), any())).thenThrow(new ExternalAuthException(Reason.INVALID_TICKET, "mã không hợp lệ"));

        mvc.perform(post("/internal/auth/external/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET"));
    }

    // ---------- endpoint cần đăng nhập ----------

    @Test
    void lienKetCuaToiVaGoLienKetCanDangNhap() throws Exception {
        mvc.perform(get("/internal/auth/me/external")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/internal/auth/me/external/google")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/auth/me/external/google/link").param("returnUrl", RETURN_URL))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(list, unlink);
    }

    @Test
    void lienKetCuaToiTraDanhSachChiCanDangNhapKhongCanKhoaQuyen() throws Exception {
        when(list.execute(userId)).thenReturn(List.of(new ExternalLoginResponse("google", "a@x.vn", NOW, null)));

        // token không có permission nào, không tenantId: @AuthenticatedOnly vẫn qua
        mvc.perform(get("/internal/auth/me/external").with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("google"))
                .andExpect(jsonPath("$[0].email").value("a@x.vn"));
    }

    @Test
    void goLienKetTra204VaChuanHoaTenNhaCungCap() throws Exception {
        mvc.perform(delete("/internal/auth/me/external/GOOGLE").with(user()))
                .andExpect(status().isNoContent());
        verify(unlink).execute(userId, "google");
    }

    @Test
    void goLienKetKhongTonTaiTra404() throws Exception {
        org.mockito.Mockito.doThrow(new ExternalAuthException(Reason.EXTERNAL_LOGIN_NOT_FOUND, "chưa liên kết"))
                .when(unlink).execute(any(), anyString());

        mvc.perform(delete("/internal/auth/me/external/google").with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXTERNAL_LOGIN_NOT_FOUND"));
    }

    @Test
    void batDauLienKetTraJsonAuthorizationUrlVaCookieCheDoLink() throws Exception {
        mvc.perform(get("/internal/auth/me/external/google/link").param("returnUrl", RETURN_URL).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value(AUTH_URL))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.startsWith("lms_ext=")));
    }

    @Test
    void batDauLienKetReturnUrlXauTra400() throws Exception {
        mvc.perform(get("/internal/auth/me/external/google/link").param("returnUrl", "http://evil.example/")
                        .with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETURN_URL_NOT_ALLOWED"));
    }

    @Test
    void cacDuongDanNgoaiNhomCongKhaiKhongMoRong() throws Exception {
        // POST login ngoài không nằm trong danh sách công khai; GET exchange cũng không
        mvc.perform(get("/internal/auth/external/exchange")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/auth/external/google/start")).andExpect(status().isUnauthorized());
    }
}
