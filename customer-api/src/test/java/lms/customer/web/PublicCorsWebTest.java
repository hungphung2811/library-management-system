package lms.customer.web;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.web.AuthController;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.web.BookController;
import lms.customer.config.PublicCorsConfiguration;
import lms.customer.config.SecurityConfiguration;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.web.OrderController;
import lms.customer.site.client.SiteClient;
import lms.customer.site.web.SiteController;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.storefront.web.ShopController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS qua chuỗi filter thật (bộ lọc CORS đăng ký ở tầng servlet trước Spring Security): lỗi 401/404/503 vẫn mang header
 * CORS khi origin được phép, origin lạ chỉ mất header (không đổi mã/thân), preflight luôn 204 không tới controller.
 */
@WebMvcTest({SiteController.class, ShopController.class, AuthController.class, BookController.class,
        OrderController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class, PublicCorsConfiguration.class})
@TestPropertySource(properties = {"app.public-cors.mode=ENFORCE",
        "app.public-cors.platform-origins=https://saas.example.vn",
        "app.public-cors.dev-origins=http://localhost:5173"})
class PublicCorsWebTest {

    private static final String SHOP_A_ORIGIN = "https://shop-a.test";

    @Autowired MockMvc mvc;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean CatalogClient catalog;
    @MockitoBean ShopClient shop;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void shopA() {
        StorefrontAccess a = new StorefrontAccess(UUID.randomUUID(), "shop-a", "A", "svc", 300, Set.of(SHOP_A_ORIGIN));
        when(storefronts.tryResolve("shop-a")).thenReturn(Optional.of(a));
        when(storefronts.resolve("shop-a")).thenReturn(a);
        when(storefronts.tryResolve("khong-co")).thenReturn(Optional.empty());
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
    }

    @Test
    void preflightOriginCuaTenantLa204CoHeaderVaKhongToiController() throws Exception {
        mvc.perform(options("/public/t/shop-a/orders").header("Origin", SHOP_A_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", SHOP_A_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET, POST, OPTIONS"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        verifyNoInteractions(shop, catalog, auth);
    }

    @Test
    void preflightOriginLaVaNullVaKhongOriginLa204KhongHeader() throws Exception {
        for (String origin : new String[] {"https://evil.test", "null", null}) {
            var req = options("/public/t/shop-a/orders").header("Access-Control-Request-Method", "POST");
            if (origin != null) {
                req.header("Origin", origin);
            }
            mvc.perform(req).andExpect(status().isNoContent())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Test
    void loi401CuaRouteDonHangVanMangCorsKhiOriginDuocPhep() throws Exception {
        mvc.perform(get("/public/t/shop-a/orders").header("Origin", SHOP_A_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", SHOP_A_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Correlation-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        // Origin lạ: cùng 401, cùng thân, chỉ mất header CORS.
        mvc.perform(get("/public/t/shop-a/orders").header("Origin", "https://evil.test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void loi404CuaHangKhongKhaDungMangCorsChiVoiOriginNenTang() throws Exception {
        mvc.perform(get("/public/t/khong-co/books").header("Origin", "https://saas.example.vn"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://saas.example.vn"))
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        mvc.perform(get("/public/t/khong-co/books").header("Origin", SHOP_A_ORIGIN))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
    }

    @Test
    void loi503KhiSalesChetVanMangCors() throws Exception {
        when(catalog.list(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(PublicApiException.serviceUnavailable());
        mvc.perform(get("/public/t/shop-a/books").header("Origin", SHOP_A_ORIGIN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Access-Control-Allow-Origin", SHOP_A_ORIGIN));
    }

    @Test
    void duongSiteVaRefreshCuChiNhanOriginNenTangVaDev() throws Exception {
        mvc.perform(options("/public/site/pages").header("Origin", SHOP_A_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/public/auth/refresh").header("Origin", SHOP_A_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/public/auth/refresh").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(post("/public/auth/refresh").header("Origin", "https://saas.example.vn")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://saas.example.vn"));
    }

    @Test
    void phanHoiLuonCoVaryOrigin() throws Exception {
        mvc.perform(get("/public/t/shop-a/orders")).andExpect(header().stringValues("Vary", org.hamcrest.Matchers.hasItem("Origin")));
    }
}
