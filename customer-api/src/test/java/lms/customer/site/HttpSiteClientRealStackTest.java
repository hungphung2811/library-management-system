package lms.customer.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lms.customer.config.ResilienceProperties;
import lms.customer.config.ServiceClients;
import lms.customer.config.ServicesProperties;
import lms.customer.site.client.http.HttpSiteClient;
import lms.customer.site.dto.SiteDtos.BusinessType;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClientResponseException;

/**
 * HttpSiteClient đi qua ĐÚNG ServiceClients (JdkClientHttpRequestFactory + DownstreamCallInterceptor) tới HTTP server thật
 * trong JVM trả thân giống system-service (ErrorResponse: code, message, details, timestamp).
 */
class HttpSiteClientRealStackTest {

    HttpServer server;
    volatile int status;
    volatile String contentType;
    volatile byte[] body = new byte[0];
    volatile boolean chunked;
    final AtomicReference<String> lastPath = new AtomicReference<>();
    final AtomicReference<String> lastBody = new AtomicReference<>();
    HttpSiteClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.getResponseHeaders().add("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : (chunked ? 0 : body.length));
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        ServiceClients clients = new ServiceClients(new ServicesProperties(url, url, Duration.ofSeconds(2),
                Duration.ofSeconds(5)), new ResilienceProperties(4), new SimpleMeterRegistry());
        client = new HttpSiteClient(clients.create(ServiceClients.SYSTEM, url));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"thong diep noi bo\",\"details\":[\"x\"],"
                + "\"timestamp\":\"2026-11-17T01:02:03.456789Z\"}";
    }

    private void respond(int st, String ct, String text, boolean chunk) {
        status = st;
        contentType = ct;
        body = text.getBytes(StandardCharsets.UTF_8);
        chunked = chunk;
    }

    private static ContactSubmission submission() {
        return new ContactSubmission("An", "an@example.vn", "0900000000", "Thu vien X", BusinessType.LIBRARY, "xin chao", true);
    }

    @Test
    void getPage404ThatLaPublicApiException() {
        for (String ct : new String[] {"application/json", "application/json;charset=UTF-8"}) {
            for (boolean chunk : new boolean[] {false, true}) {
                respond(404, ct, error("CMS_PAGE_NOT_FOUND"), chunk);
                PublicApiException ex = assertThrows(PublicApiException.class, () -> client.getPage("khong-co"));
                assertEquals(404, ex.status().value());
                assertEquals("CMS_PAGE_NOT_FOUND", ex.code());
            }
        }
        assertEquals("GET /internal/cms/public/pages/khong-co", lastPath.get());
    }

    @Test
    void getPage404KhongPhaiMaCmsHoacThanLaGiuLoiGocKhongNpe() {
        String[][] cases = {{"", null}, {"", "application/json"}, {"<html>404</html>", "text/html"},
                {"khong phai json", "application/json"}, {"{\"code\":123}", "application/json"},
                {"{\"message\":\"x\"}", "application/json"}, {"[]", "application/json"}, {"null", "application/json"},
                {error("SHOP_NOT_AVAILABLE"), "application/json"}};
        for (String[] c : cases) {
            respond(404, c[1], c[0], false);
            assertThrows(RestClientResponseException.class, () -> client.getPage("x"), c[0]);
        }
    }

    @Test
    void getPage200TraVeTrang() {
        respond(200, "application/json", "{\"slug\":\"gioi-thieu\",\"title\":\"Giới thiệu\",\"bodyHtml\":\"<p>a</p>\","
                + "\"publishedAt\":\"2026-11-01T00:00:00Z\"}", false);
        PageView p = client.getPage("gioi-thieu");
        assertEquals("gioi-thieu", p.slug());
        assertEquals("<p>a</p>", p.bodyHtml());
    }

    @Test
    void listPagesThanRongHoacNullLaDanhSachRong() {
        respond(200, "application/json", "[]", false);
        assertEquals(List.of(), client.listPages());
        respond(200, "application/json", "null", false);
        assertEquals(List.of(), client.listPages());
        respond(204, null, "", false);
        assertEquals(List.of(), client.listPages());
        respond(200, "application/json", "[{\"slug\":\"a\",\"title\":\"A\",\"sortOrder\":1}]", false);
        List<PageSummaryView> l = client.listPages();
        assertEquals(1, l.size());
        assertEquals("GET /internal/cms/public/pages", lastPath.get());
    }

    static Stream<Arguments> loi400() {
        return Stream.of(Arguments.of("CONTACT_REQUEST_INVALID"), Arguments.of("CONSENT_REQUIRED"),
                Arguments.of("VALIDATION_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("loi400")
    void submit400ChuyenTiepRestClientResponseExceptionCoMaDocDuoc(String code) {
        for (boolean chunk : new boolean[] {false, true}) {
            respond(400, "application/json", error(code), chunk);
            RestClientResponseException ex = assertThrows(RestClientResponseException.class,
                    () -> client.submitContactRequest(submission()));
            assertEquals(400, ex.getStatusCode().value());
            assertTrue(ex.getResponseBodyAsString().contains(code));
            assertEquals(code, lms.customer.web.DownstreamErrorCode.of(ex));
        }
        assertEquals("POST /internal/cms/public/contact-requests", lastPath.get());
        assertTrue(lastBody.get().contains("\"consent\":true"), lastBody.get());
    }

    @Test
    void submitLoi400ThanRongHoacHtmlKhongNpe() {
        String[][] cases = {{"", null}, {"<html>bad</html>", "text/html"}, {"khong phai json", "application/json"},
                {"{\"code\":null}", "application/json"}};
        for (int st : new int[] {400, 404, 422}) {
            for (String[] c : cases) {
                respond(st, c[1], c[0], false);
                RestClientResponseException ex = assertThrows(RestClientResponseException.class,
                        () -> client.submitContactRequest(submission()));
                assertEquals(null, lms.customer.web.DownstreamErrorCode.of(ex));
            }
        }
    }

    @Test
    void submitThanhCong201VaLoi5xx() {
        respond(201, null, "", false);
        client.submitContactRequest(submission());
        respond(503, "application/json", error("INTERNAL_ERROR"), false);
        RestClientResponseException ex = assertThrows(RestClientResponseException.class,
                () -> client.submitContactRequest(submission()));
        assertNotNull(ex.getStatusCode());
        assertTrue(ex.getStatusCode().is5xxServerError());
    }
}
