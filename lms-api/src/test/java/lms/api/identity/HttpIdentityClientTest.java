package lms.api.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sun.net.httpserver.HttpServer;
import lms.api.config.ServicesProperties;
import lms.api.identity.client.http.HttpAccessClient;
import lms.api.identity.client.http.HttpAuthClient;
import lms.api.identity.client.http.HttpUserClient;
import lms.api.identity.dto.AuthDtos.ChangePasswordRequest;
import lms.api.identity.dto.UserDtos.SearchUsersQuery;
import lms.api.support.TokenRelayInterceptor;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/** Client identity: đường dẫn /internal/**, ánh xạ DTO, chuyển token, và KHÔNG follow redirect khi gọi service thật. */
class HttpIdentityClientTest {

    static final UUID ROLE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void goiDungDuongDanNoiBoVaChuyenTiepToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://identity")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient rc = builder.build();
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        server.expect(requestTo("http://identity/internal/auth/login")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.username").value("alice"))
                .andRespond(withSuccess("{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"tokenType\":\"Bearer\","
                        + "\"expiresIn\":900,\"mustChangePassword\":true,\"them\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://identity/internal/users?keyword=a%20b&size=5"))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":0,\"size\":5}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://identity/internal/roles/" + ROLE + "/permissions/BorrowingController/borrow"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"id\":\"" + ROLE + "\",\"name\":\"r\","
                        + "\"description\":\"d\",\"isAdmin\":true,\"permissions\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://identity/internal/auth/password")).andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        var token = new HttpAuthClient(rc).login("alice", "pw");
        assertEquals("a", token.accessToken());
        assertTrue(token.mustChangePassword());
        assertEquals(5, new HttpUserClient(rc).search(new SearchUsersQuery("a b", null, null, null, 5)).size());
        var role = new HttpAccessClient(rc).revokePermission(ROLE, "BorrowingController/borrow");
        assertTrue(role.isAdmin());
        new HttpAuthClient(rc).changePassword(new ChangePasswordRequest("a", "12345678"));
        server.verify();
    }

    @Test
    void khongFollowRedirectVaChuyenNguyenLocationSetCookieVaGuiCookieLmsExt() throws Exception {
        AtomicReference<String> cookieNhan = new AtomicReference<>();
        AtomicReference<String> queryNhan = new AtomicReference<>();
        AtomicInteger daGoiDich = new AtomicInteger();
        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/internal/auth/external/google/start", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + idp.getAddress().getPort() + "/idp/authorize?s=1");
            ex.getResponseHeaders().add("Set-Cookie", "lms_ext=v1; Path=/api/auth; HttpOnly; SameSite=Lax");
            ex.getResponseHeaders().add("Cache-Control", "no-store");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        idp.createContext("/internal/auth/external/google/callback", ex -> {
            cookieNhan.set(ex.getRequestHeaders().getFirst("Cookie"));
            queryNhan.set(ex.getRequestURI().getRawQuery());
            ex.getResponseHeaders().add("Location", "http://localhost:3000/#externalCode=T1");
            ex.getResponseHeaders().add("Set-Cookie", "lms_ext=; Path=/api/auth; Max-Age=0");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        idp.createContext("/idp/authorize", ex -> {
            daGoiDich.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        idp.createContext("/internal/auth/external/hong/callback", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        idp.start();
        try {
            String base = "http://127.0.0.1:" + idp.getAddress().getPort();
            ServicesProperties props = new ServicesProperties(base, base, base, base, Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
            HttpAuthClient client = new HttpAuthClient(IdentityClientConfiguration.noRedirectClient(base, props));

            ResponseEntity<byte[]> start = client.externalStart("google", "http://localhost:3000/");
            assertEquals(302, start.getStatusCode().value());
            assertEquals(base + "/idp/authorize?s=1", start.getHeaders().getFirst(HttpHeaders.LOCATION));
            assertEquals(List.of("lms_ext=v1; Path=/api/auth; HttpOnly; SameSite=Lax"),
                    start.getHeaders().get(HttpHeaders.SET_COOKIE));
            assertEquals("no-store", start.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
            assertEquals(0, daGoiDich.get(), "client không được tự follow redirect");

            ResponseEntity<byte[]> cb = client.externalCallback("google", "c o", "st", null, "v1");
            assertEquals(302, cb.getStatusCode().value());
            assertEquals("http://localhost:3000/#externalCode=T1", cb.getHeaders().getFirst(HttpHeaders.LOCATION));
            assertEquals(List.of("lms_ext=; Path=/api/auth; Max-Age=0"), cb.getHeaders().get(HttpHeaders.SET_COOKIE));
            assertEquals("lms_ext=v1", cookieNhan.get());
            assertEquals("code=c%20o&state=st", queryNhan.get());

            cookieNhan.set("chua-xoa");
            client.externalCallback("google", "c", "s", null, null);
            assertNull(cookieNhan.get());

            // 5xx của service không được chuyển như phản hồi thô
            assertThrows(HttpServerErrorException.class, () -> client.externalCallback("hong", "c", "s", null, null));
        } finally {
            idp.stop(0);
        }
    }
}
