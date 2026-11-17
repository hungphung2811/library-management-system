package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.web.AuthController;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.web.BookController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.ContactRequestBody;
import lms.customer.shop.web.ContactRequestController;
import lms.customer.shop.web.OrderController;
import lms.customer.site.client.SiteClient;
import lms.customer.site.web.SiteController;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.storefront.web.ShopController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * T4 (ADR-0015): POST /public/t/{code}/contact-requests. Thứ tự 404 (cửa hàng) rồi 401/403 (phiên), thân gửi sales chỉ
 * có 6 trường allowlist, 201 không thân no-store, VALIDATION_FAILED, CONSENT_REQUIRED, 429 của sales chuyển với mã nhưng
 * thông điệp do BFF viết, FORBIDDEN không lộ khóa, 5xx/mất kết nối thành 503, phương thức khác không vào controller,
 * log không chứa dữ liệu cá nhân.
 */
@WebMvcTest({SiteController.class, ShopController.class, AuthController.class, BookController.class,
        OrderController.class, ContactRequestController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class})
class ContactRequestWebTest {

    private static final String PATH = "/public/t/shop-a/contact-requests";
    private static final String NAME = "Nguyễn Văn Bí-Mật";
    private static final String EMAIL = "bi.mat@example.vn";
    private static final String PHONE = "0987654321";
    private static final String TEXT = "Noi dung rieng tu 7f3a";

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean CatalogClient catalog;
    @MockitoBean ShopClient shop;
    @MockitoBean JwtDecoder jwtDecoder;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(tenantA, "shop-a", "Hiệu sách A", "svc-token-a", 300));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    private RequestPostProcessor customer(UUID tenant) {
        return jwt().jwt(j -> j.tokenValue("user-token").subject("u1").claim("tenantId", tenant.toString())
                .audience(List.of("lms-storefront")));
    }

    private static String body() {
        return "{\"topic\":\"ORDER\",\"fullName\":\"" + NAME + "\",\"email\":\"" + EMAIL + "\",\"phone\":\"" + PHONE
                + "\",\"message\":\"" + TEXT + "\",\"consent\":true}";
    }

    private static HttpClientErrorException downstream(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        return mvc.perform(post(PATH).with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void assertNoPiiInLogs() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent e : new ArrayList<>(logs.list)) {
            all.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                all.append(e.getThrowableProxy().getMessage()).append('\n');
            }
        }
        assertThat(all.toString()).doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain("Noi dung rieng tu")
                .doesNotContain("Bí-Mật");
    }

    // ---------- AC1, AC5: thành công, thân allowlist ----------

    @Test
    void thanhCong201KhongThanNoStoreChuyenTokenKhachVaChiSauTruongAllowlist() throws Exception {
        String evil = body().replace("{", "{\"status\":\"CLOSED\",\"customerUserId\":\"x\",\"tenantId\":\""
                + tenantB + "\",\"id\":\"" + UUID.randomUUID() + "\",\"createdAt\":\"2000-01-01T00:00:00Z\","
                + "\"orderId\":\"" + UUID.randomUUID() + "\",\"version\":9,");
        send(evil).andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Location")).andExpect(content().string(""));
        ArgumentCaptor<ContactRequestBody> captor = ArgumentCaptor.forClass(ContactRequestBody.class);
        verify(shop).submitContactRequest(eq("user-token"), captor.capture()); // token KHÁCH, không phải svc-token-a
        assertThat(ContactRequestBody.class.getRecordComponents()).extracting("name")
                .containsExactly("topic", "fullName", "email", "phone", "message", "consent");
        assertThat(captor.getValue().toString()).doesNotContain(EMAIL).doesNotContain(NAME);
        assertNoPiiInLogs();
    }

    // ---------- AC2: thứ tự và token ----------

    @Test
    void khongTokenLa401KhongGoiGi() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shop, storefronts);
    }

    @Test
    void tokenThieuAudHoacTokenDichVuLa401() throws Exception {
        RequestPostProcessor noAud = jwt().jwt(j -> j.tokenValue("t").claim("tenantId", tenantA.toString()));
        RequestPostProcessor lmsApi = jwt().jwt(j -> j.tokenValue("t").claim("tenantId", tenantA.toString())
                .audience(List.of("lms-api")));
        RequestPostProcessor svc = jwt().jwt(j -> j.tokenValue("svc").claim("svc", "customer-api")
                .claim("tenantId", tenantA.toString()).audience(List.of("lms-storefront")));
        for (RequestPostProcessor token : List.of(noAud, lmsApi, svc)) {
            mvc.perform(post(PATH).with(token).contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(shop);
    }

    @Test
    void tokenTenantKhacLa403ShopSessionMismatch() throws Exception {
        mvc.perform(post(PATH).with(customer(tenantB)).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SHOP_SESSION_MISMATCH"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(shop, never()).submitContactRequest(any(), any());
    }

    @Test
    void cuaHangKhongKhaDungLa404TruocMoiKiemPhienKeCaTokenTenantKhac() throws Exception {
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        for (UUID tenant : List.of(tenantA, tenantB)) {
            mvc.perform(post("/public/t/khong-co/contact-requests").with(customer(tenant))
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        }
        verifyNoInteractions(shop);
    }

    @Test
    void phuongThucKhacTrenDuongDanKhongVaoController() throws Exception {
        for (var req : List.of(get(PATH), put(PATH), delete(PATH), get(PATH + "/" + UUID.randomUUID()),
                post(PATH + "/" + UUID.randomUUID()), post(PATH + "/"))) {
            MvcResult r = mvc.perform(req.with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn();
            assertThat(r.getResponse().getStatus()).as(req.toString()).isIn(401, 403, 404, 405);
        }
        verifyNoInteractions(shop);
    }

    // ---------- AC3, AC4 ----------

    @Test
    void thieuHoacSaiTruongLa400ValidationFailedKhongGoiSalesKhongLoGiaTri() throws Exception {
        List<String> bad = List.of(
                body().replace("\"ORDER\"", "\"LA\""),
                body().replace("\"topic\":\"ORDER\",", ""),
                body().replace("\"fullName\":\"" + NAME + "\",", ""),
                body().replace(NAME, "   "),
                body().replace(EMAIL, ""),
                body().replace("\"message\":\"" + TEXT + "\"", "\"message\":\"\""),
                body().replace(PHONE, "1".repeat(21)),
                body().replace(NAME, "a".repeat(401)),
                body().replace(TEXT, "a".repeat(8001)),
                body().replace(TEXT, "a\\u0000b"),
                body().replace(NAME, "a\\u0007b"),
                "{}", "[]");
        for (String b : bad) {
            MvcResult r = send(b).andExpect(status().isBadRequest()).andReturn();
            assertThat(r.getResponse().getContentAsString()).doesNotContain(EMAIL).doesNotContain(PHONE)
                    .doesNotContain("Noi dung rieng tu");
        }
        verify(shop, never()).submitContactRequest(any(), any());
        send(body().replace("\"ORDER\"", "\"LA\"")).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertNoPiiInLogs();
    }

    @Test
    void consentFalseThieuHoacKhongPhaiBooleanLaConsentRequiredHoac400() throws Exception {
        send(body().replace("\"consent\":true", "\"consent\":false")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        send(body().replace(",\"consent\":true", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        MvcResult str = send(body().replace("\"consent\":true", "\"consent\":\"true\"")).andReturn();
        assertThat(str.getResponse().getStatus()).isEqualTo(400);
        verify(shop, never()).submitContactRequest(any(), any());
    }

    @Test
    void hoaLoiCuaSalesMaRoleKhachThieuKhoaLa403KhongLoTenKhoa() throws Exception {
        doThrow(downstream(HttpStatus.FORBIDDEN,
                "{\"code\":\"FORBIDDEN\",\"message\":\"Thiếu khóa ShopContactController/create\"}"))
                .when(shop).submitContactRequest(any(), any());
        MvcResult r = send(body()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("ShopContactController");
    }

    @Test
    void salesTatModuleLa404ShopNotAvailable() throws Exception {
        doThrow(downstream(HttpStatus.FORBIDDEN, "{\"code\":\"MODULE_NOT_ENABLED\",\"message\":\"x\"}"))
                .when(shop).submitContactRequest(any(), any());
        send(body()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
    }

    @Test
    void salesTra429ChuyenVoiMaVaThongDiepDoBffViet() throws Exception {
        doThrow(downstream(HttpStatus.TOO_MANY_REQUESTS,
                "{\"code\":\"SHOP_CONTACT_LIMIT_EXCEEDED\",\"message\":\"noi bo " + EMAIL + "\",\"details\":[\"x\"]}"))
                .when(shop).submitContactRequest(any(), any());
        MvcResult r = send(body()).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SHOP_CONTACT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("Bạn đã gửi quá nhiều yêu cầu, vui lòng thử lại sau")).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain(EMAIL).doesNotContain("noi bo");
        assertNoPiiInLogs();
    }

    @Test
    void salesTra400InvalidRequestThanhValidationFailedVaConsentRequiredChuyenNguyen() throws Exception {
        doThrow(downstream(HttpStatus.BAD_REQUEST, "{\"code\":\"INVALID_REQUEST\",\"message\":\"email: " + EMAIL + "\"}"))
                .when(shop).submitContactRequest(any(), any());
        MvcResult r = send(body()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain(EMAIL);
        doThrow(downstream(HttpStatus.BAD_REQUEST, "{\"code\":\"CONSENT_REQUIRED\",\"message\":\"x\"}"))
                .when(shop).submitContactRequest(any(), any());
        send(body()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void maLoiNoiBoKhongCoTrongDanhSachChoPhepBiLocThanhRequestRejected() throws Exception {
        doThrow(downstream(HttpStatus.CONFLICT, "{\"code\":\"SHOP_CONTACT_STATE_CONFLICT\",\"message\":\"x\"}"))
                .when(shop).submitContactRequest(any(), any());
        send(body()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    @Test
    void salesLoi5xxHoacMatKetNoiLa503KhongLogPii() throws Exception {
        doThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", HttpHeaders.EMPTY,
                ("{\"message\":\"" + EMAIL + "\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .when(shop).submitContactRequest(any(), any());
        send(body()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        doThrow(new ResourceAccessException("timeout", new SocketTimeoutException(EMAIL)))
                .when(shop).submitContactRequest(any(), any());
        send(body()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        assertNoPiiInLogs();
    }
}
