package lms.customer.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lms.customer.auth.client.http.HttpAuthClient;
import lms.customer.config.ResilienceProperties;
import lms.customer.config.ServiceClients;
import lms.customer.config.ServicesProperties;
import lms.customer.storefront.client.http.HttpStorefrontAccessClient;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hồi quy lỗi 500 khi sai mật khẩu: đi qua ĐÚNG ServiceClients (JdkClientHttpRequestFactory + DownstreamCallInterceptor)
 * tới HTTP server thật trong JVM trả đúng thân/Content-Type của identity (ErrorResponse: code, message, details,
 * timestamp; Content-Type application/json), thay vì MockRestServiceServer.
 */
class HttpAuthClientRealStackTest {

    HttpServer server;
    volatile int status;
    volatile String contentType;
    volatile byte[] body;
    volatile boolean chunked;
    final AtomicInteger hits = new AtomicInteger();
    HttpAuthClient client;
    HttpStorefrontAccessClient storefront;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            exchange.getResponseHeaders().add("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
            exchange.getResponseHeaders().add("Vary", "Origin");
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
        client = new HttpAuthClient(clients.create(ServiceClients.IDENTITY, url));
        storefront = new HttpStorefrontAccessClient(clients.create(ServiceClients.IDENTITY, url), "s");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String error(String code) {
        return "{\"code\":\"" + code + "\",\"message\":\"Thông tin đăng nhập không hợp lệ\",\"details\":[],"
                + "\"timestamp\":\"2026-11-17T01:02:03.456789Z\"}";
    }

    private void respond(int st, String ct, String text, boolean chunk) {
        status = st;
        contentType = ct;
        body = text.getBytes(StandardCharsets.UTF_8);
        chunked = chunk;
    }

    static Stream<Arguments> bonMa() {
        return Stream.of(Arguments.of(401, "UNAUTHORIZED"), Arguments.of(403, "NO_TENANT_ACCESS"),
                Arguments.of(423, "ACCOUNT_LOCKED"), Arguments.of(403, "ACCOUNT_DISABLED"));
    }

    @ParameterizedTest
    @MethodSource("bonMa")
    void bonMaCuaIdentityThatDeuLaLoginFailed(int st, String code) {
        for (String ct : new String[] {"application/json", "application/json;charset=UTF-8"}) {
            for (boolean chunk : new boolean[] {false, true}) {
                respond(st, ct, error(code), chunk);
                PublicApiException ex = assertThrows(PublicApiException.class, () -> client.login("shop-a", "an", "sai"));
                assertEquals(401, ex.status().value());
                assertEquals("LOGIN_FAILED", ex.code());
            }
        }
    }

    @Test
    void thanRongKhongPhaiJsonVaMaLaKhongBaoGioLaNpe() {
        String[][] cases = {{"", null}, {"", "application/json"}, {"<html>401</html>", "text/html"},
                {"khong phai json", "application/json"}, {"{\"code\":123}", "application/json"},
                {"{\"message\":\"x\"}", "application/json"}, {"[]", "application/json"}, {"null", "application/json"},
                {error("MA_LA"), "application/json"}};
        for (int st : new int[] {401, 403, 423}) {
            for (String[] c : cases) {
                respond(st, c[1], c[0], false);
                // Không phải bốn mã đã biết: chuyển tiếp lỗi gốc (RestClientResponseException) để tầng trên ra 503/mã tương ứng.
                assertThrows(RestClientResponseException.class, () -> client.login("shop-a", "an", "sai"));
            }
        }
    }

    @Test
    void refreshVaLogoutChuyenTiepLoiGocKhongNpe() {
        respond(401, "application/json", error("UNAUTHORIZED"), false);
        assertThrows(RestClientResponseException.class, () -> client.refresh("r"));
        assertThrows(RestClientResponseException.class, () -> client.logout("r"));
        respond(401, null, "", false);
        assertThrows(RestClientResponseException.class, () -> client.refresh("r"));
    }

    @Test
    void storefrontClientVoiPhanHoiLoiThat() {
        respond(404, "application/json", error("SHOP_NOT_AVAILABLE"), false);
        assertEquals(true, storefront.issue("x").isEmpty());
        for (String[] c : new String[][] {{"", null}, {"<html/>", "text/html"}, {"null", "application/json"}}) {
            respond(404, c[1], c[0], false);
            PublicApiException ex = assertThrows(PublicApiException.class, () -> storefront.issue("x"));
            assertEquals(503, ex.status().value());
        }
        respond(401, "application/json", error("UNAUTHORIZED"), false);
        assertThrows(PublicApiException.class, () -> storefront.issue("x"));
    }
}
