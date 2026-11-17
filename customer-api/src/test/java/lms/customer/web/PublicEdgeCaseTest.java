package lms.customer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.site.client.SiteClient;
import lms.customer.site.dto.SiteDtos.PageView;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpServerErrorException;

/**
 * Ca bất thường ở đầu vào công khai, chạy trên Tomcat thật (cổng ngẫu nhiên, không cần Docker): đường dẫn mã hóa kép,
 * Unicode lạ, Host/X-Forwarded-*, HEAD/OPTIONS, header/URL quá lớn, request đồng thời (cache stampede), rò rỉ log.
 * Các client phía sau được giả (không có identity/system thật). Ca mang mã BUG-x là lỗi đã tái hiện và đã sửa.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.junit.jupiter.api.Timeout(value = 60, unit = TimeUnit.SECONDS)
class PublicEdgeCaseTest {

    @LocalServerPort int port;
    @MockitoBean StorefrontAccessClient identityStorefront;
    @MockitoBean AuthClient auth;
    @MockitoBean SiteClient site;
    @MockitoBean JwtDecoder jwtDecoder;

    @Autowired lms.customer.storefront.StorefrontAccessCache cache;

    final AtomicInteger issueCalls = new AtomicInteger();
    final List<String> issuedCodes = Collections.synchronizedList(new ArrayList<>());
    ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        reset(identityStorefront, auth, site);
        issueCalls.set(0);
        issuedCodes.clear();
        // Mã bắt đầu bằng "shop" tồn tại; còn lại không khả dụng. Mỗi ca dùng mã riêng vì bộ đệm là singleton của context.
        when(identityStorefront.issue(anyString())).thenAnswer(inv -> {
            String code = inv.getArgument(0);
            issueCalls.incrementAndGet();
            issuedCodes.add(code);
            Thread.sleep(150);
            if (!code.startsWith("shop")) {
                return Optional.empty();
            }
            return Optional.of(new StorefrontAccess(UUID.randomUUID(), code, "Hiệu sách " + code, "svc-token-bi-mat", 300));
        });
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    // ---------- tiện ích ----------

    record Raw(int status, String head, String body) {
        String header(String name) {
            for (String line : head.split("\r\n")) {
                int i = line.indexOf(':');
                if (i > 0 && line.substring(0, i).equalsIgnoreCase(name)) {
                    return line.substring(i + 1).trim();
                }
            }
            return null;
        }
    }

    Raw raw(String method, String path, String... headers) throws IOException {
        StringBuilder sb = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        for (String h : headers) {
            sb.append(h).append("\r\n");
        }
        sb.append("\r\n");
        return rawSend(sb.toString());
    }

    Raw rawSend(String request) throws IOException {
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(20_000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                s.getInputStream().transferTo(buf);
            } catch (IOException ignored) {
                // server đóng sớm sau khi trả lỗi: vẫn đọc được phần đã nhận
            }
            String all = buf.toString(StandardCharsets.UTF_8);
            if (all.isEmpty()) {
                return new Raw(-1, "", "");
            }
            int split = all.indexOf("\r\n\r\n");
            String head = split < 0 ? all : all.substring(0, split);
            String body = split < 0 ? "" : all.substring(split + 4);
            int status = Integer.parseInt(head.substring(9, 12));
            return new Raw(status, head, body);
        }
    }

    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();

    HttpResponse<String> http(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static String code() {
        return "shop-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------- HEAD / OPTIONS ----------

    /**
     * BUG-1 (Thấp): HEAD trên các endpoint công khai GET trả 401/403 vì PUBLIC_API chỉ so khớp GET. Crawler, uptime
     * monitor, CDN và link preview dùng HEAD; 401 "Cần đăng nhập" gây hiểu lầm.
     */
    @Test
    void headTrenEndpointCongKhaiGetPhaiNhuGetKhongThan() throws Exception {
        String c = code();
        assertEquals(200, raw("HEAD", "/public/t/" + c).status());
        assertEquals(200, raw("HEAD", "/public/site/pages").status());
    }

    @Test
    void optionsKhongLoNoiBoVaKhongThanh5xx() throws Exception {
        for (String path : new String[] {"/public/t/" + code(), "/internal/books", "/api/users", "/actuator/env", "/error"}) {
            Raw r = raw("OPTIONS", path);
            assertTrue(r.status() < 500, path + " -> " + r.status());
            assertFalse(r.body().contains("Exception"), path);
            assertFalse(r.body().toLowerCase().contains("tomcat"), path);
        }
    }

    // ---------- Đường dẫn bất thường ----------

    @Test
    void duongDanMaHoaKepLaMaSaiDinhDang404KhongGoiIdentity() throws Exception {
        // %2561 -> Tomcat giải mã một lần thành "%61": StrictHttpFirewall của Spring Security từ chối đường dẫn chứa "%"
        // (RequestRejectedException -> sendError(400) -> /error bị denyAll -> 403 FORBIDDEN JSON). Chấp nhận 403 hoặc 404,
        // miễn là JSON gọn, không gọi identity (xem ghi chú INFO-1 trong báo cáo: mã không thống nhất 404 SHOP_NOT_AVAILABLE).
        Raw r = raw("GET", "/public/t/%2561bc");
        assertTrue(r.status() == 403 || r.status() == 404, "status=" + r.status() + " " + r.body());
        assertTrue(r.body().contains("FORBIDDEN") || r.body().contains("SHOP_NOT_AVAILABLE"), r.body());
        assertEquals(0, issueCalls.get());
    }

    @Test
    void duongDanNguyHiemKhongBaoGioLa5xxHoac200() throws Exception {
        String[] paths = {
                "/public/t/%2e%2e/internal/books", "/public/t/..%2f..%2finternal", "/public/t/a%2Fb", "/public/t/a%00b",
                "/public/t/a;jsessionid=1", "/public/t/;/", "/public/site/pages/..;/..;/internal",
                "/public/site/pages/%2e%2e", "/public/site/pages/%252e%252e", "/public//t/abc", "/public/t/abc//",
                "/public/t/%EF%BC%A1%EF%BC%A2", "/public/t/%F0%9F%98%80%F0%9F%98%80", "/public/t/%E2%80%AEabc",
                "/public/t/%C0%AE%C0%AE", "/public/t/%zz", "/public/site/pages/%E0%A4%A"};
        for (String p : paths) {
            Raw r = raw("GET", p);
            assertTrue(r.status() >= 400 && r.status() < 500, p + " -> " + r.status() + " " + r.body());
            assertFalse(r.body().contains("at lms.") || r.body().contains("java.lang"), p);
        }
        assertEquals(0, issueCalls.get(), "không được gọi identity với mã rác: " + issuedCodes);
    }

    @Test
    void maRatDaiKhongGoiIdentityVaKhongLoi() throws Exception {
        Raw r = raw("GET", "/public/t/" + "a".repeat(3000));
        assertEquals(404, r.status());
        assertTrue(r.body().contains("SHOP_NOT_AVAILABLE"));
        assertEquals(0, issueCalls.get());
    }

    /**
     * BUG-2 (Thấp): normalize dùng toLowerCase(Locale.ROOT): ký tự Kelvin U+212A thành 'k' ASCII, nên URL
     * /public/t/%E2%84%AAids (Unicode) phân giải cùng tenant "kids": nhiều URL cho một tenant, vượt ý định
     * "chuẩn hóa trim + chữ thường" của ADR-0011 2.2 (mã chỉ ASCII).
     */
    @Test
    void maChuaKyTuUnicodeBienThanhAsciiKhiHaChuBiTuChoi() throws Exception {
        Raw r = raw("GET", "/public/t/shop-%E2%84%AAids");
        assertEquals(404, r.status(), r.body());
        assertEquals(0, issueCalls.get());
    }

    @Test
    void hoaThuongVaKhoangTrangDuocChuanHoaThanhMotKhoaDemDuyNhat() throws Exception {
        String c = code();
        assertEquals(200, raw("GET", "/public/t/" + c.toUpperCase()).status());
        assertEquals(200, raw("GET", "/public/t/%20" + c + "%20").status());
        assertEquals(200, raw("GET", "/public/t/" + c).status());
        assertEquals(1, issueCalls.get(), "ba biến thể phải dùng chung một mục đệm: " + issuedCodes);
        assertEquals(List.of(c), issuedCodes);
    }

    // ---------- Header lạ ----------

    @Test
    void hostVaXForwardedKhongAnhHuongPhanHoiVaKhongSinhRedirect() throws Exception {
        String c = code();
        Raw base = raw("GET", "/public/t/" + c);
        Raw evil = rawSend("GET /public/t/" + c + " HTTP/1.1\r\nHost: evil.example:1337\r\nX-Forwarded-Host: evil.example\r\n"
                + "X-Forwarded-Proto: https\r\nX-Forwarded-For: 1.2.3.4, 5.6.7.8\r\nX-Forwarded-Prefix: /x\r\nForwarded: for=evil;host=evil\r\n"
                + "X-Original-URL: /internal/books\r\nX-Rewrite-URL: /internal/books\r\nX-HTTP-Method-Override: DELETE\r\n"
                + "X-Correlation-Id: " + "a".repeat(200) + "\r\nConnection: close\r\n\r\n");
        assertEquals(200, evil.status());
        assertEquals(base.body().replaceAll("\"timestamp\".*", ""), evil.body().replaceAll("\"timestamp\".*", ""));
        assertEquals(null, evil.header("Location"));
        assertFalse(evil.head().contains("evil.example"), evil.head());
    }

    @Test
    void acceptLanguageKhacNhauKhongDoiPhanHoi() throws Exception {
        String c = code();
        String vi = raw("GET", "/public/t/" + c, "Accept-Language: vi-VN,vi;q=0.9").body();
        String zh = raw("GET", "/public/t/" + c, "Accept-Language: zh-CN").body();
        String junk = raw("GET", "/public/t/" + c, "Accept-Language: " + "x-".repeat(500) + ";q=banana").body();
        assertEquals(vi, zh);
        assertEquals(vi, junk);
        Raw bad = raw("GET", "/public/t/khong-co-" + UUID.randomUUID(), "Accept-Language: en");
        assertEquals(404, bad.status());
        assertTrue(bad.body().contains("Cửa hàng không khả dụng"), "thông điệp tiếng Việt cố định: " + bad.body());
    }

    @Test
    void headerVaUrlQuaLonKhongRoXacHeThongVaTraLoi4xx() throws Exception {
        Raw bigHeader = raw("GET", "/public/t/" + code(), "X-Big: " + "h".repeat(20_000));
        Raw bigUrl = raw("GET", "/public/t/" + "a".repeat(20_000));
        for (Raw r : List.of(bigHeader, bigUrl)) {
            assertTrue(r.status() == -1 || (r.status() >= 400 && r.status() < 500), "status=" + r.status());
            assertFalse(r.body().toLowerCase().contains("apache tomcat"), r.body());
            assertFalse(r.body().contains("Exception"), r.body());
            String server = r.header("Server");
            assertTrue(server == null || !server.toLowerCase().contains("tomcat"), String.valueOf(server));
        }
    }

    @Test
    void headerBaoMatVaCacheControlTrenPhanHoiCongKhai() throws Exception {
        Raw ok = raw("GET", "/public/t/" + code());
        assertEquals("max-age=30, public", ok.header("Cache-Control"));
        // Spring Security không được ghi Pragma/Expires làm CDN/proxy cũ bỏ qua max-age
        assertEquals(null, ok.header("Pragma"), ok.head());
        assertEquals(null, ok.header("Expires"), ok.head());
        assertEquals("nosniff", ok.header("X-Content-Type-Options"));
        Raw notFound = raw("GET", "/public/t/khong-co-" + UUID.randomUUID());
        assertEquals("no-store", notFound.header("Cache-Control"));
        Raw denied = raw("GET", "/internal/books");
        assertTrue(denied.status() == 401 || denied.status() == 403 || denied.status() == 404);
        assertTrue(denied.header("Cache-Control").contains("no-store"));
    }

    // ---------- Đồng thời (cache stampede) ----------

    @Test
    void tramRequestDongThoiCungMotMaChiGoiIdentityMotLan() throws Exception {
        String c = code();
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return http("GET", "/public/t/" + c, null).statusCode();
            }));
        }
        go.countDown();
        for (Future<Integer> f : fs) {
            assertEquals(200, f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(1, issueCalls.get(), "stampede: " + issueCalls.get() + " lời gọi sang identity cho cùng một mã");
    }

    @Test
    void tramRequestDongThoiCungMotMaKhongTonTaiChiGoiIdentityMotLan() throws Exception {
        String c = "none-" + UUID.randomUUID().toString().substring(0, 8);
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return http("GET", "/public/t/" + c, null).statusCode();
            }));
        }
        go.countDown();
        for (Future<Integer> f : fs) {
            assertEquals(404, f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(1, issueCalls.get());
    }

    @Test
    void nhieuMaKhacNhauMoiMaMotLanGoiVaKhongMaNaoBiLoi() throws Exception {
        int n = 60;
        ExecutorService pool = Executors.newFixedThreadPool(30);
        List<Future<Integer>> fs = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String c = code();
            codes.add(c);
            fs.add(pool.submit(() -> http("GET", "/public/t/" + c, null).statusCode()));
        }
        for (Future<Integer> f : fs) {
            assertEquals(200, f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertEquals(n, issueCalls.get());
        assertEquals(n, issuedCodes.stream().distinct().count());
    }

    @Test
    void identityLoiKhongBiDemMoiRequestGoiLaiVaTraLoi503() throws Exception {
        String c = code();
        org.mockito.Mockito.doAnswer(inv -> {
            issueCalls.incrementAndGet();
            throw PublicApiException.serviceUnavailable();
        }).when(identityStorefront).issue(anyString());
        for (int i = 0; i < 3; i++) {
            Raw r = raw("GET", "/public/t/" + c);
            assertEquals(503, r.status());
            assertTrue(r.body().contains("SERVICE_UNAVAILABLE"));
        }
        // không đệm lỗi (đúng ADR); không có circuit breaker nên mỗi request đều chạm identity
        assertEquals(3, issueCalls.get());
    }

    // ---------- Thân request bất thường ----------

    static final String CONTACT_TPL = "{\"fullName\":\"Nguyễn An\",\"email\":\"an@example.vn\",\"phone\":\"0900000000\","
            + "\"organization\":\"Thư viện X\",\"businessType\":\"LIBRARY\",\"message\":\"%s\",\"consent\":%s,\"website\":\"%s\"}";

    HttpResponse<String> postContact(String message, String consent, String website) throws Exception {
        return http("POST", "/public/site/contact-requests", String.format(CONTACT_TPL, message, consent, website),
                "Content-Type", "application/json");
    }

    @Test
    void jsonLongSauMoiNghinTangLa400KhongPhai500() throws Exception {
        String deep = "[".repeat(100_000);
        HttpResponse<String> r = http("POST", "/public/site/contact-requests", deep, "Content-Type", "application/json");
        // Thân 100 KB vượt trần 16 KB của /public/** nên bị 413 ngay ở bộ lọc kích thước (THIẾU-4); nếu nhỏ hơn trần thì
        // Jackson chặn độ sâu và trả 400 (xem PublicInputLimitsTest.jsonLongQua32TangLa400). Không bao giờ là 5xx.
        assertTrue(r.statusCode() == 400 || r.statusCode() == 413, "status=" + r.statusCode());
        assertTrue(r.body().contains("VALIDATION_FAILED") || r.body().contains("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void thanQuaLonKhongBi5xxVaKhongGuiSangSystem() throws Exception {
        String huge = "x".repeat(3 * 1024 * 1024);
        HttpResponse<String> r = postContact(huge, "true", "");
        assertTrue(r.statusCode() == 400 || r.statusCode() == 413, "status=" + r.statusCode());
        org.mockito.Mockito.verifyNoInteractions(site);
    }

    /**
     * BUG-3 (Trung bình): ký tự NUL (và điều khiển khác) trong lời nhắn qua được @NotBlank/@Size rồi chuyển sang
     * system-service; PostgreSQL không lưu được 0x00 nên phía sau sẽ lỗi 500 và khách thấy 503 chung. Đầu vào công khai
     * phải bị từ chối ở customer-api (400 VALIDATION_FAILED).
     */
    @Test
    void kyTuNulTrongLoiNhanBiTuChoi400() throws Exception {
        HttpResponse<String> r = postContact("xin\\u0000chao", "true", "");
        assertEquals(400, r.statusCode(), r.body());
        org.mockito.Mockito.verifyNoInteractions(site);
    }

    /** BUG-4 (Thấp): consent chấp nhận chuỗi "true" và số 1 do Jackson ép kiểu; đồng ý phải là boolean true tường minh. */
    @Test
    void consentChuoiHoacSoKhongPhaiDongYTuongMinh() throws Exception {
        for (String consent : new String[] {"\"true\"", "1", "\"yes\""}) {
            HttpResponse<String> r = postContact("ok", consent, "");
            assertEquals(400, r.statusCode(), "consent=" + consent + " -> " + r.statusCode());
        }
        org.mockito.Mockito.verifyNoInteractions(site);
    }

    /**
     * BUG-5 (Thấp): honeypot có @Size(max=200): bot nhét chuỗi dài vào ô bẫy nhận 400 VALIDATION_FAILED, khác với 201
     * "thành công giả", nên bot phát hiện ra ô bẫy và học cách tránh.
     */
    @Test
    void honeypotDaiVanTra201ImLang() throws Exception {
        HttpResponse<String> r = postContact("ok", "true", "s".repeat(500));
        assertEquals(201, r.statusCode(), r.body());
        org.mockito.Mockito.verifyNoInteractions(site);
    }

    @Test
    void contentTypeFormUrlEncodedVaMultipartKhongPhai500() throws Exception {
        for (String ct : new String[] {"application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "text/plain", "application/xml"}) {
            HttpResponse<String> r = http("POST", "/public/site/contact-requests", "a=b", "Content-Type", ct);
            assertTrue(r.statusCode() == 415 || r.statusCode() == 400, ct + " -> " + r.statusCode());
        }
    }

    @Test
    void usernameVaMatKhauKhongDuocLoiQuaDoDaiVaUnicodeLa() throws Exception {
        String c = code();
        when(auth.login(anyString(), anyString(), anyString())).thenReturn(new IdentityTokenView("a", "r", 300));
        String longUser = "u".repeat(101);
        assertEquals(400, http("POST", "/public/t/" + c + "/auth/login",
                "{\"username\":\"" + longUser + "\",\"password\":\"x\"}", "Content-Type", "application/json").statusCode());
        assertEquals(400, http("POST", "/public/t/" + c + "/auth/login",
                "{\"username\":\"u\",\"password\":\"" + "p".repeat(73) + "\"}", "Content-Type", "application/json").statusCode());
        assertEquals(200, http("POST", "/public/t/" + c + "/auth/login",
                "{\"username\":\"người-dùng-😀\",\"password\":\"mật-khẩu\"}", "Content-Type", "application/json").statusCode());
    }

    // ---------- Rò rỉ log ----------

    @Test
    void duLieuCaNhanVaTokenKhongXuatHienTrongLog() throws Exception {
        String email = "bi.mat.an@example.vn";
        String phone = "0987654321";
        String password = "MatKhau-Rat-Bi-Mat-9";
        String refresh = "refresh-token-bi-mat";
        String c = code();

        // 1. gửi form hợp lệ, form sai, JSON hỏng chứa email, honeypot
        postContact("lời nhắn riêng tư", "true", "");
        http("POST", "/public/site/contact-requests", "{\"email\":\"" + email + "\"", "Content-Type", "application/json");
        http("POST", "/public/site/contact-requests",
                "{\"fullName\":\"A\",\"email\":\"" + email + "\",\"phone\":\"" + phone + "\",\"organization\":\"O\","
                        + "\"businessType\":\"SAI\",\"message\":\"m\",\"consent\":true}", "Content-Type", "application/json");
        postContact("m", "true", "bot");
        // 2. phía sau lỗi 5xx có chứa email trong thân, và lỗi bất ngờ có email trong thông điệp
        when(site.listPages()).thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x",
                HttpHeaders.EMPTY, ("{\"message\":\"loi " + email + "\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        http("GET", "/public/site/pages", null);
        when(site.getPage("trang")).thenThrow(new IllegalStateException("bat ngo " + email));
        http("GET", "/public/site/pages/trang", null);
        // 3. đăng nhập thành công và thất bại, refresh/logout
        when(auth.login(anyString(), anyString(), anyString())).thenReturn(new IdentityTokenView("jwt-bi-mat", refresh, 300));
        http("POST", "/public/t/" + c + "/auth/login", "{\"username\":\"nguoi.dung\",\"password\":\"" + password + "\"}",
                "Content-Type", "application/json");
        http("POST", "/public/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}", "Content-Type", "application/json");
        http("POST", "/public/auth/logout", "{\"refreshToken\":\"" + refresh + "\"}", "Content-Type", "application/json");
        when(site.getPage("ok")).thenReturn(new PageView("ok", "t", "<p>b</p>", null));

        // Ngoại lệ bất ngờ có chứa email: log.error in nguyên thông điệp ngoại lệ, nên ca này ghi nhận có rò rỉ
        // thông điệp của ngoại lệ (chỉ trong log, không ra phản hồi). Loại ca đó khỏi so khớp chung, kiểm riêng bên dưới.
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : new ArrayList<>(logs.list)) {
            StringBuilder sb = new StringBuilder(e.getFormattedMessage());
            IThrowableProxy t = e.getThrowableProxy();
            while (t != null) {
                sb.append(' ').append(t.getMessage());
                t = t.getCause();
            }
            lines.add(sb.toString());
        }
        String all = String.join("\n", lines);
        for (String secret : new String[] {phone, password, refresh, "jwt-bi-mat", "svc-token-bi-mat", "lời nhắn riêng tư"}) {
            assertFalse(all.contains(secret), "log lộ: " + secret);
        }
        // email xuất hiện trong log chỉ được phép do ngoại lệ bất ngờ (IllegalStateException) ở bước 2
        long emailLines = lines.stream().filter(l -> l.contains(email)).count();
        assertTrue(emailLines <= 1, "email xuất hiện ở " + emailLines + " dòng log: " + lines.stream().filter(l -> l.contains(email)).toList());
    }

    /**
     * BUG-6 (Thấp): handler Exception.class gọi log.error("...", ex) in nguyên thông điệp và stack của ngoại lệ bất ngờ;
     * nếu thông điệp chứa dữ liệu khách (ví dụ lỗi parse/ánh xạ kèm giá trị) thì vào log. Nên log tên lớp + mã tương quan.
     */
    @Test
    void ngoaiLeBatNgoKhongInThongDiepChuaDuLieuKhachVaoLog() throws Exception {
        String email = "rieng.tu@example.vn";
        when(site.getPage("trang")).thenThrow(new IllegalStateException("bat ngo " + email));
        http("GET", "/public/site/pages/trang", null);
        for (ILoggingEvent e : new ArrayList<>(logs.list)) {
            IThrowableProxy t = e.getThrowableProxy();
            assertFalse(e.getFormattedMessage().contains(email));
            while (t != null) {
                assertFalse(String.valueOf(t.getMessage()).contains(email), "log lộ thông điệp ngoại lệ");
                t = t.getCause();
            }
        }
    }

    // ---------- Khác ----------

    @Test
    void actuatorChiCoHealthVaInfo() throws Exception {
        assertEquals(200, raw("GET", "/actuator/health").status());
        for (String p : new String[] {"/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/prometheus",
                "/actuator/heapdump", "/actuator/loggers", "/actuator/mappings", "/actuator"}) {
            int s = raw("GET", p).status();
            assertTrue(s == 401 || s == 403 || s == 404, p + " -> " + s);
        }
        String body = raw("GET", "/actuator/health").body();
        assertFalse(body.contains("diskSpace"), "health không được lộ chi tiết: " + body);
    }

    @Test
    void nhanTenantKhacNhauKhongDungChungKetQua() throws Exception {
        String a = code();
        String b = code();
        assertNotEquals(raw("GET", "/public/t/" + a).body(), raw("GET", "/public/t/" + b).body());
        assertEquals(2, issueCalls.get());
    }
}
