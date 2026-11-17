package lms.customer.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.web.AuthController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.site.client.SiteClient;
import lms.customer.site.web.SiteController;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.storefront.web.ShopController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Hồi quy: identity trả 200 nhưng null/thiếu token thì khách nhận 503 chung, không 500 và không 200 thiếu accessToken. */
@WebMvcTest({SiteController.class, ShopController.class, AuthController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class})
class PublicIdentityIncompleteResponseTest {

    @Autowired MockMvc mvc;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String LOGIN = "/public/t/shop-a/auth/login";
    private static final String REFRESH = "/public/auth/refresh";
    private static final String LOGIN_BODY = "{\"username\":\"an\",\"password\":\"mat-khau-1\"}";
    private static final String REFRESH_BODY = "{\"refreshToken\":\"ref\"}";

    /** null, {} (Jackson cho chuỗi null và expiresIn 0), accessToken rỗng/trắng, thiếu refreshToken, expiresIn 0 hoặc âm. */
    private static List<IdentityTokenView> incomplete() {
        return java.util.Arrays.asList(null, new IdentityTokenView(null, null, 0), new IdentityTokenView("", "r", 900),
                new IdentityTokenView("   ", "r", 900), new IdentityTokenView(null, "r", 900),
                new IdentityTokenView("a", null, 900), new IdentityTokenView("a", "", 900),
                new IdentityTokenView("a", "r", 0), new IdentityTokenView("a", "r", -1));
    }

    private void assertUnavailable(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        r.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("INTERNAL_ERROR"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
    }

    @Test
    void loginThieuTokenLa503() throws Exception {
        when(storefronts.resolve(anyString())).thenReturn(new StorefrontAccess(java.util.UUID.randomUUID(), "shop-a",
                "Shop A", "svc-token", 900));
        for (IdentityTokenView view : incomplete()) {
            when(auth.login(anyString(), anyString(), anyString())).thenReturn(view);
            assertUnavailable(mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY)));
        }
    }

    @Test
    void refreshThieuTokenLa503() throws Exception {
        for (IdentityTokenView view : incomplete()) {
            when(auth.refresh(anyString())).thenReturn(view);
            assertUnavailable(mvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(REFRESH_BODY)));
        }
    }

    @Test
    void loginVaRefreshDayDuVanLa200() throws Exception {
        when(storefronts.resolve(anyString())).thenReturn(new StorefrontAccess(java.util.UUID.randomUUID(), "shop-a",
                "Shop A", "svc-token", 900));
        when(auth.login(anyString(), anyString(), anyString())).thenReturn(new IdentityTokenView("a", "r", 900));
        when(auth.refresh(anyString())).thenReturn(new IdentityTokenView("a2", "r2", 900));
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("a"));
        mvc.perform(post(REFRESH).contentType(MediaType.APPLICATION_JSON).content(REFRESH_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("a2"));
    }

    /** Thân JSON thật từ identity (qua HttpAuthClient thật): null, rỗng, {}, accessToken rỗng đều thành 503, không NPE. */
    @Test
    void thanJsonThatThieuTokenQuaHttpAuthClientThanh503() {
        String[] bodies = {"null", "", "{}", "{\"refreshToken\":\"r\",\"expiresIn\":900}",
                "{\"accessToken\":\"\",\"refreshToken\":\"r\",\"expiresIn\":900}",
                "{\"accessToken\":\"a\",\"refreshToken\":\"r\"}"};
        when(storefronts.resolve(anyString())).thenReturn(new StorefrontAccess(java.util.UUID.randomUUID(), "shop-a",
                "Shop A", "svc-token", 900));
        for (String body : bodies) {
            for (String op : new String[] {"login", "refresh"}) {
                org.springframework.web.client.RestClient.Builder builder =
                        org.springframework.web.client.RestClient.builder().baseUrl("http://identity");
                org.springframework.test.web.client.MockRestServiceServer server =
                        org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
                server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                                "http://identity/internal/auth/" + op))
                        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                                .withSuccess(body, MediaType.APPLICATION_JSON));
                AuthController controller = new AuthController(new lms.customer.auth.client.http.HttpAuthClient(
                        builder.build()), storefronts);
                org.junit.jupiter.api.function.Executable call = "login".equals(op)
                        ? () -> controller.login("shop-a", new lms.customer.auth.dto.AuthDtos.LoginRequest("an", "pw"))
                        : () -> controller.refresh(new lms.customer.auth.dto.AuthDtos.RefreshRequest("ref"));
                PublicApiException ex = org.junit.jupiter.api.Assertions.assertThrows(PublicApiException.class, call,
                        op + " body=[" + body + "]");
                org.junit.jupiter.api.Assertions.assertEquals(503, ex.status().value());
                org.junit.jupiter.api.Assertions.assertEquals("SERVICE_UNAVAILABLE", ex.code());
            }
        }
    }

    @Test
    void logoutKhongThanVanLa204() throws Exception {
        mvc.perform(post("/public/auth/logout").contentType(MediaType.APPLICATION_JSON).content(REFRESH_BODY))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
    }

    @Test
    void trangGioiThieuNullLa503() throws Exception {
        when(site.getPage("gioi-thieu")).thenReturn(null);
        mvc.perform(get("/public/site/pages/gioi-thieu")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
