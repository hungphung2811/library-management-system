package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.util.List;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.web.AuthController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.site.client.SiteClient;
import lms.customer.site.web.SiteController;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.web.ShopController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * T-C4 bổ sung (ADR-0011 2.9): rà bề mặt tấn công của customer-api qua chuỗi bảo mật thật. Mọi đường dẫn ngoài danh
 * sách cho phép bị chặn, mọi phương thức lạ không thành 2xx/5xx, thân lỗi không lộ stack/tên service/khóa nội bộ.
 */
@WebMvcTest({SiteController.class, ShopController.class, AuthController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class})
class PublicSecurityHardeningTest {

    @Autowired MockMvc mvc;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final List<String> BLOCKED = List.of(
            "/internal/books", "/internal/permissions/registrations", "/internal/storefront/access-tokens",
            "/internal/cms/pages", "/internal/cms/public/pages", "/internal/migrations",
            "/api/auth/login", "/api/cms/pages", "/api/platform/tenants", "/api/menus/me",
            "/actuator", "/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/loggers",
            "/actuator/mappings", "/actuator/prometheus", "/actuator/metrics", "/actuator/configprops",
            "/error", "/", "/public", "/public/", "/swagger-ui.html", "/v3/api-docs",
            "/.well-known/jwks.json", "/h2-console", "/public/t/shop-a/orders", "/public/t/shop-a/books",
            "/public/t/shop-a/orders/1/cancel", "/public/t/shop-a/books/1/2/3", "/public/t/shop-a/anything");

    private void expectNoLeak(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "at lms.", "org.springframework", "stackTrace", "trace",
                "identity-service", "system-service", "sales-service", "catalog-service", "localhost", "Controller/",
                "Thiếu quyền");
    }

    @Test
    void moiDuongDanNgoaiDanhSachChoPhepBiChanVoiMoiPhuongThuc() throws Exception {
        for (String path : BLOCKED) {
            for (MockHttpServletRequestBuilder b : List.of(get(path), post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{}"), put(path).contentType(MediaType.APPLICATION_JSON).content("{}"), delete(path))) {
                MvcResult r = mvc.perform(b).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s", path)
                        .isIn(401, 403, 404, 405);
                assertThat(r.getResponse().getStatus()).as("%s không được là 2xx", path).isGreaterThanOrEqualTo(400);
                expectNoLeak(r);
            }
        }
        verifyNoInteractions(site, auth, storefronts);
    }

    @Test
    void tokenNguoiDungHopLeCungKhongMoDuocInternalVaApi() throws Exception {
        for (String path : List.of("/internal/books", "/api/auth/login", "/api/cms/pages", "/actuator/env",
                "/public/t/shop-a/orders")) {
            MvcResult r = mvc.perform(get(path).with(jwt().jwt(j -> j.claim("tenantId", "x").claim("admin", true)
                    .claim("platformAdmin", true)))).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isIn(403, 404);
            expectNoLeak(r);
        }
    }

    @Test
    void phuongThucLaTrenDuongDanCongKhaiKhongThanh2xxHoac5xx() throws Exception {
        for (MockHttpServletRequestBuilder b : List.of(
                delete("/public/site/pages"), put("/public/site/pages/gioi-thieu"),
                post("/public/site/pages"), post("/public/t/shop-a"), delete("/public/t/shop-a"),
                get("/public/auth/refresh"), get("/public/auth/logout"), get("/public/site/contact-requests"),
                get("/public/t/shop-a/auth/login"), head("/public/auth/refresh"))) {
            MvcResult r = mvc.perform(b).andReturn();
            assertThat(r.getResponse().getStatus()).isBetween(400, 499);
            expectNoLeak(r);
        }
    }

    @Test
    void bodyHongHoacKhongPhaiJsonLa400GonKhongLoGi() throws Exception {
        for (String path : List.of("/public/site/contact-requests", "/public/t/shop-a/auth/login",
                "/public/auth/refresh", "/public/auth/logout")) {
            for (String body : List.of("", "not json", "[]", "null", "{\"a\":", "\"x\"", "{}")) {
                MvcResult r = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s <- %s", path, body).isEqualTo(400);
                assertThat(r.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
                expectNoLeak(r);
            }
        }
    }

    /**
     * LỖI ĐÃ TÁI HIỆN (tester, ADR-0011 2.9): Content-Type không hỗ trợ rơi vào handler Exception chung, trả 500
     * INTERNAL_ERROR và log ERROR kèm stack trace cho mỗi request công khai. Đã sửa: 415 UNSUPPORTED_MEDIA_TYPE.
     */
    @Test
    void contentTypeKhongHoTroLa415KhongPhai500() throws Exception {
        for (String path : List.of("/public/site/contact-requests", "/public/t/shop-a/auth/login",
                "/public/auth/refresh", "/public/auth/logout")) {
            MvcResult r = mvc.perform(post(path).contentType(MediaType.TEXT_PLAIN).content("x")).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(415);
            assertThat(r.getResponse().getContentAsString()).contains("UNSUPPORTED_MEDIA_TYPE");
            expectNoLeak(r);
        }
    }

    @Test
    void acceptKhongHoTroLa406GonKhong500() throws Exception {
        MvcResult r = mvc.perform(get("/public/site/pages").accept(MediaType.APPLICATION_XML)).andReturn();
        assertThat(r.getResponse().getStatus()).isBetween(400, 499);
        expectNoLeak(r);
    }

    @Test
    void maLoi4xxLaTuServicePhiaSauKhongLamNoHandler() throws Exception {
        // 418 không có trong HttpStatus enum cũ; handler phải giữ 4xx, không ném IllegalArgumentException -> 500
        when(auth.refresh("tok")).thenThrow(new RestClientResponseException("x", 418, "I'm a teapot",
                null, "{\"code\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        MvcResult r = mvc.perform(post("/public/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"tok\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(418);
        assertThat(r.getResponse().getContentAsString()).contains("UNAUTHORIZED");
        expectNoLeak(r);
    }

    @Test
    void honeypotKhongCoHieuLucVoiThanKhongHopLe() throws Exception {
        // bot điền honeypot nhưng thiếu trường bắt buộc vẫn bị 400 (không nhận 201 giả): hành vi hiện tại
        MvcResult r = mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"website\":\"http://spam\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(site);
    }
}
