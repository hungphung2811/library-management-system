package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClientResponseException;

/** Hồi quy BUG-1 vòng 2 (tester): kiểm chặt mã trạng thái và mã lỗi, không chỉ khoảng 4xx. */
@WebMvcTest({SiteController.class, ShopController.class, AuthController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class})
class PublicErrorRegressionTest {

    @Autowired MockMvc mvc;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final List<String> POSTS = List.of("/public/site/contact-requests", "/public/t/shop-a/auth/login",
            "/public/auth/refresh", "/public/auth/logout");

    @Test
    void contentTypeLaMoiBienTheDeu415() throws Exception {
        for (String path : POSTS) {
            for (String ct : List.of("text/plain", "application/xml", "application/x-www-form-urlencoded",
                    "multipart/form-data; boundary=x", "application/octet-stream", "image/png",
                    "text/plain;charset=UTF-16")) {
                MvcResult r = mvc.perform(post(path).header("Content-Type", ct).content("a=b")).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s %s", path, ct).isEqualTo(415);
                assertThat(r.getResponse().getContentAsString()).contains("UNSUPPORTED_MEDIA_TYPE")
                        .doesNotContain("Exception", "org.springframework");
            }
        }
    }

    @Test
    void contentTypeRacHoacHongKhong500() throws Exception {
        for (String path : POSTS) {
            for (String ct : List.of("///", "garbage", "text/", "application/json;charset=bogus-xyz", "*/*")) {
                MvcResult r = mvc.perform(post(path).header("Content-Type", ct).content("{}")).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s [%s]", path, ct).isBetween(400, 499);
            }
        }
    }

    @Test
    void acceptLaDung406() throws Exception {
        for (String path : List.of("/public/site/pages")) {
            MvcResult r = mvc.perform(get(path).accept(MediaType.APPLICATION_XML)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(406);
        }
        MvcResult bad = mvc.perform(get("/public/site/pages").header("Accept", "not/a/type")).andReturn();
        assertThat(bad.getResponse().getStatus()).isBetween(400, 499);
    }

    @Test
    void maLa4xxLaGiuNguyenVaMaNgoai4xxThanh400() throws Exception {
        for (int status : List.of(400, 401, 404, 409, 418, 422, 429, 451)) {
            doThrow(new RestClientResponseException("x", status, "s", null,
                    "{\"code\":\"VALIDATION_FAILED\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).when(auth).refresh("tok");
            MvcResult r = mvc.perform(post("/public/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"tok\"}")).andReturn();
            assertThat(r.getResponse().getStatus()).as("status %d", status).isEqualTo(status);
        }
        for (int status : List.of(302, 304)) {
            doThrow(new RestClientResponseException("x", status, "s", null,
                    "{\"code\":\"VALIDATION_FAILED\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).when(auth).refresh("tok");
            MvcResult r = mvc.perform(post("/public/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"tok\"}")).andReturn();
            assertThat(r.getResponse().getStatus()).as("status %d", status).isEqualTo(400);
        }
    }

    @Test
    void jsonSaiKieuLaThanh400() throws Exception {
        for (String body : List.of("{\"refreshToken\":{\"a\":1}}", "{\"refreshToken\":[\"x\"]}",
                "\u0000", "{\"refreshToken\":\"a\"",                 "{\"refreshToken\":\"a\"}x", "{\"refreshToken\":\"a\",\"x\":1,")) {
            doThrow(new RestClientResponseException("x", 401, "s", null,
                    "{\"code\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .when(auth).refresh(org.mockito.ArgumentMatchers.anyString());
            MvcResult r = mvc.perform(post("/public/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as(body + " -> " + r.getResponse().getContentAsString() + " ex=" + r.getResolvedException()).isIn(400, 401);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("INTERNAL_ERROR");
        }
    }

    @Test
    void duongDanLaKhong5xx() throws Exception {
        for (String path : List.of("/public/site/pages/%00", "/public/site/pages/%ff", "/public/t/%00")) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isLessThan(500);
        }
    }

    /** BUG-R2-1 (tester vòng 2): khóa JSON trùng làm Jackson ném HttpMessageConversionException, không có handler nên 500. */
    @Test
    void khoaJsonTrungKhongPhai500() throws Exception {
        for (String path : POSTS) {
            MvcResult r = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"refreshToken\":\"a\",\"refreshToken\":\"b\"}")).andReturn();
            assertThat(r.getResponse().getStatus()).as("%s -> %s", path, r.getResponse().getContentAsString())
                    .isBetween(400, 499);
        }
    }
}
