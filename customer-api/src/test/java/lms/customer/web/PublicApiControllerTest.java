package lms.customer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.web.AuthController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.site.client.SiteClient;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;
import lms.customer.site.web.SiteController;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.storefront.web.ShopController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Hợp đồng công khai của C0: công khai/denyAll, lọc lỗi, honeypot, Cache-Control, token svc, không lộ nội bộ. */
@WebMvcTest({SiteController.class, ShopController.class, AuthController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, PublicApiControllerTest.Metrics.class})
class PublicApiControllerTest {

    @TestConfiguration
    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry registry;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String CONTACT = """
            {"fullName":"Nguyễn An","email":"an@example.vn","phone":"0900000000","organization":"Thư viện X",
             "businessType":"LIBRARY","message":"Muốn dùng thử","consent":true,"website":""}""";

    private static HttpClientErrorException downstream(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    // ---------- CMS ----------

    @Test
    void danhSachTrangVaChiTietCongKhaiCoCacheControl() throws Exception {
        when(site.listPages()).thenReturn(List.of(new PageSummaryView("gioi-thieu", "Giới thiệu", 1)));
        when(site.getPage("gioi-thieu")).thenReturn(new PageView("gioi-thieu", "Giới thiệu", "<p>Xin chào</p>",
                Instant.parse("2026-11-16T01:00:00Z")));

        mvc.perform(get("/public/site/pages"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$[0].slug").value("gioi-thieu"));
        mvc.perform(get("/public/site/pages/gioi-thieu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyHtml").value("<p>Xin chào</p>"))
                .andExpect(jsonPath("$.publishedAt").value("2026-11-16T01:00:00Z"));
    }

    @Test
    void slugSaiDinhDangLa404MaKhongGoiSystem() throws Exception {
        mvc.perform(get("/public/site/pages/Co_Hoa_Sai"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CMS_PAGE_NOT_FOUND"));
        verifyNoInteractions(site);
    }

    @Test
    void trangChuaXuatBanChuyenTiep404CmsPageNotFoundVoiThongDiepCuaCustomerApi() throws Exception {
        when(site.getPage("nhap")).thenThrow(downstream(HttpStatus.NOT_FOUND,
                "{\"code\":\"CMS_PAGE_NOT_FOUND\",\"message\":\"Trang nhap cms_pages id=7 chua xuat ban\"}"));
        mvc.perform(get("/public/site/pages/nhap"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CMS_PAGE_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("cms_pages"))));
    }

    // ---------- Form liên hệ ----------

    @Test
    void formHopLeTra201KhongThanVaChuyenSangSystem() throws Exception {
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content(CONTACT))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        verify(site).submitContactRequest(any(ContactSubmission.class));
    }

    @Test
    void honeypotCoGiaTriVan201NhungKhongChuyenVaTangBoDem() throws Exception {
        double before = registry.counter("customer.contact.honeypot").count();
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTACT.replace("\"website\":\"\"", "\"website\":\"http://spam.example\"")))
                .andExpect(status().isCreated());
        verify(site, never()).submitContactRequest(any());
        assertEquals(before + 1, registry.counter("customer.contact.honeypot").count());
    }

    @Test
    void thieuHoacKhongDongYConsentLa400ConsentRequired() throws Exception {
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTACT.replace("\"consent\":true", "\"consent\":false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTACT.replace("\"consent\":true,", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        verify(site, never()).submitContactRequest(any());
    }

    @Test
    void formSaiDinhDangHoacQuaDaiLa400ValidationKhongLoGiaTri() throws Exception {
        String longMessage = "x".repeat(2001);
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTACT.replace("an@example.vn", "khong-phai-email")
                                .replace("Muốn dùng thử", longMessage)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("khong-phai-email"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("xxxx"))));
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(CONTACT.replace("LIBRARY", "KHAC")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content("{khong-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void maLoi400CuaSystemDuocAnhXaVeMaCongKhaiGon() throws Exception {
        for (String code : List.of("VALIDATION_ERROR", "CONTACT_REQUEST_INVALID")) {
            org.mockito.Mockito.doThrow(downstream(HttpStatus.BAD_REQUEST,
                            "{\"code\":\"" + code + "\",\"message\":\"email an@example.vn sai\","
                                    + "\"details\":[\"email: an@example.vn\"]}"))
                    .when(site).submitContactRequest(any());
            mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content(CONTACT))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("Dữ liệu không hợp lệ"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("an@example.vn"))));
        }
    }

    @Test
    void consentRequiredCuaSystemDuocChuyenTiepVaMaLaKhongRoiVaoRequestRejected() throws Exception {
        org.mockito.Mockito.doThrow(downstream(HttpStatus.BAD_REQUEST,
                        "{\"code\":\"CONSENT_REQUIRED\",\"message\":\"thong diep noi bo\"}"))
                .when(site).submitContactRequest(any());
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content(CONTACT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("noi bo"))));
    }

    @Test
    void ma400LaKhongBiThayBangRequestRejected() throws Exception {
        org.mockito.Mockito.doThrow(downstream(HttpStatus.BAD_REQUEST, "{\"code\":\"MA_LA\",\"message\":\"x\"}"))
                .when(site).submitContactRequest(any());
        mvc.perform(post("/public/site/contact-requests").contentType(MediaType.APPLICATION_JSON).content(CONTACT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    // ---------- Storefront / đăng nhập ----------

    @Test
    void thongTinTenantChiCoCodeNameVaHaiCoTinhNang() throws Exception {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(UUID.randomUUID(), "shop-a", "Hiệu sách A", "svc-token", 300));
        mvc.perform(get("/public/t/shop-a"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, public"))
                .andExpect(jsonPath("$.code").value("shop-a"))
                .andExpect(jsonPath("$.name").value("Hiệu sách A"))
                // identity cũ không có modules: coi như chỉ SALES
                .andExpect(jsonPath("$.shopEnabled").value(true))
                .andExpect(jsonPath("$.libraryEnabled").value(false))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("svc-token"))));
    }

    @Test
    void tenantKhongKhaDungLaMotMaMotThanCoDinh() throws Exception {
        when(storefronts.resolve(any())).thenThrow(PublicApiException.shopNotAvailable());
        String first = mvc.perform(get("/public/t/khong-co"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(get("/public/t/bi-ngung"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(strip(first), strip(second));
    }

    private static String strip(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]*\"", "");
    }

    @Test
    void dangNhapEpTenantTheoDuongDanBoQuaTenantCodeTrongBodyVaLocPhanHoi() throws Exception {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(UUID.randomUUID(), "shop-a", "A", "svc-token", 300));
        when(auth.login(eq("shop-a"), eq("an"), eq("mat-khau-1")))
                .thenReturn(new IdentityTokenView("acc", "ref", 900));
        mvc.perform(post("/public/t/shop-a/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"an\",\"password\":\"mat-khau-1\",\"tenantCode\":\"tenant-khac\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.availableTenants").doesNotExist());
    }

    /** Chạy HttpAuthClient thật trước identity giả: mọi lý do đăng nhập thất bại phải ra cùng một phản hồi. */
    @Test
    void dangNhapThatBaiThanPhanHoiGiongHetNhauChoMoiLyDoKeCaKhoaVaVoHieu() throws Exception {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(UUID.randomUUID(), "shop-a", "A", "svc-token", 300));
        String[][] cases = {
                {"401", "UNAUTHORIZED"}, {"403", "NO_TENANT_ACCESS"}, {"423", "ACCOUNT_LOCKED"}, {"403", "ACCOUNT_DISABLED"}};
        String expected = null;
        for (String[] c : cases) {
            org.springframework.web.client.RestClient.Builder builder =
                    org.springframework.web.client.RestClient.builder().baseUrl("http://identity");
            org.springframework.test.web.client.MockRestServiceServer identity =
                    org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
            identity.expect(org.springframework.test.web.client.match.MockRestRequestMatchers
                            .requestTo("http://identity/internal/auth/login"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                            .withStatus(HttpStatus.valueOf(Integer.parseInt(c[0])))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"" + c[1] + "\",\"message\":\"thong diep noi bo\"}"));
            lms.customer.auth.client.http.HttpAuthClient real = new lms.customer.auth.client.http.HttpAuthClient(builder.build());
            org.mockito.Mockito.doAnswer(inv -> real.login(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))
                    .when(auth).login(any(), any(), any());
            var result = mvc.perform(post("/public/t/shop-a/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"an\",\"password\":\"x\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                    .andReturn().getResponse();
            String body = strip(result.getContentAsString(StandardCharsets.UTF_8));
            if (expected == null) {
                expected = body;
            }
            assertEquals(expected, body, "Thân phản hồi lệch ở trường hợp " + c[1]);
            assertEquals(false, body.contains("LOCKED") || body.contains("DISABLED") || body.contains("khóa"));
        }
    }

    @Test
    void refreshVanChuyenTiepMaKhoaVaVoHieuCuaTaiKhoan() throws Exception {
        when(auth.refresh("ref")).thenThrow(downstream(HttpStatus.LOCKED,
                "{\"code\":\"ACCOUNT_LOCKED\",\"message\":\"x\"}"));
        mvc.perform(post("/public/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void refreshChuyenTiepRefreshTokenSupersededVoiThongDiepCuaCustomerApi() throws Exception {
        when(auth.refresh("ref")).thenThrow(downstream(HttpStatus.UNAUTHORIZED,
                "{\"code\":\"REFRESH_TOKEN_SUPERSEDED\",\"message\":\"chi tiet noi bo sessionId=abc\"}"));
        String body = mvc.perform(post("/public/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_SUPERSEDED"))
                .andExpect(jsonPath("$.message").value("Phiên vừa được làm mới ở nơi khác, vui lòng thử lại"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(false, body.contains("noi bo") || body.contains("sessionId"));
    }

    @Test
    void dangNhapVaoTenantKhongKhaDungKhongChamIdentity() throws Exception {
        when(storefronts.resolve(any())).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(post("/public/t/khong-co/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"an\",\"password\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(auth);
    }

    @Test
    void refreshVaLogoutCongKhaiBoQuaAuthorizationHetHan() throws Exception {
        when(auth.refresh("ref")).thenReturn(new IdentityTokenView("acc2", "ref2", 900));
        mvc.perform(post("/public/auth/refresh").header("Authorization", "Bearer token-het-han")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("acc2"));
        mvc.perform(post("/public/auth/logout").header("Authorization", "Bearer token-het-han")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isNoContent());
        verify(auth).logout("ref");
    }

    // ---------- Bảo mật ----------

    @Test
    void internalApiVaDuongDanLaBiChanDenyAll() throws Exception {
        mvc.perform(get("/internal/books")).andExpect(status().isForbidden());
        mvc.perform(get("/api/menus/me")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/books").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(get("/api/menus/me").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mvc.perform(post("/public/t/shop-a/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void tokenDichVuSvcBiTuChoi401() throws Exception {
        mvc.perform(get("/public/site/pages").with(jwt().jwt(j -> j.claim("svc", "customer-api"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(site);
    }

    // ---------- Lỗi phía sau ----------

    @Test
    void loiPhiaSauLa503ChungKhongLoTenServiceHoacHost() throws Exception {
        when(site.listPages()).thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "err",
                HttpHeaders.EMPTY, "{\"message\":\"catalog-service host=10.0.0.5 NullPointerException at lms.x\"}"
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        String body = mvc.perform(get("/public/site/pages"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        for (String leak : new String[] {"catalog", "10.0.0.5", "NullPointer", "lms.x", "at "}) {
            org.junit.jupiter.api.Assertions.assertFalse(body.contains(leak), "lộ: " + leak);
        }
    }

    @Test
    void matKetNoiLa503() throws Exception {
        when(site.listPages()).thenThrow(new ResourceAccessException("Connection refused: system-service:8086"));
        String body = mvc.perform(get("/public/site/pages"))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("system-service"));
    }

    @Test
    void forbiddenKhongLoTenKhoaMaModuleNotEnabledThanhShopNotAvailable() throws Exception {
        when(site.getPage("a")).thenThrow(downstream(HttpStatus.FORBIDDEN,
                "{\"code\":\"FORBIDDEN\",\"message\":\"Thiếu quyền: OrderController/placeOrder\"}"));
        String body = mvc.perform(get("/public/site/pages/a"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("OrderController"));

        when(site.getPage("b")).thenThrow(downstream(HttpStatus.FORBIDDEN,
                "{\"code\":\"MODULE_NOT_ENABLED\",\"message\":\"SALES\"}"));
        mvc.perform(get("/public/site/pages/b"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        when(site.getPage("c")).thenThrow(downstream(HttpStatus.FORBIDDEN,
                "{\"code\":\"TENANT_REQUIRED\",\"message\":\"x\"}"));
        mvc.perform(get("/public/site/pages/c"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
    }

    @Test
    void maTrongDanhSachChoPhepGiuMaBoDetailsVaDungThongDiepCuaCustomerApi() throws Exception {
        when(site.getPage("kho")).thenThrow(downstream(HttpStatus.CONFLICT,
                "{\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"Chi con 2 cuon, sku=ABC\",\"details\":[\"available=2\"]}"));
        String body = mvc.perform(get("/public/site/pages/kho"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("available"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("sku"));
    }

    @Test
    void maLaNgoaiDanhSachCho4xxThanhRequestRejectedKhongChepThongDiep() throws Exception {
        when(site.getPage("la")).thenThrow(downstream(HttpStatus.UNPROCESSABLE_ENTITY,
                "{\"code\":\"INTERNAL_RULE_X\",\"message\":\"chi tiet noi bo\"}"));
        mvc.perform(get("/public/site/pages/la"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("noi bo"))));
    }

    @Test
    void loiBatNgoKhongCoStackTrace() throws Exception {
        when(site.listPages()).thenThrow(new IllegalStateException("boom lms.customer.site.SiteController.java:42"));
        String body = mvc.perform(get("/public/site/pages"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("boom"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("lms.customer"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("trace"));
    }

    @Test
    void duongDanCongKhaiKhongCoControllerLa404GonKhongLoGi() throws Exception {
        mvc.perform(get("/public/site/khong-co/gi-ca"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
