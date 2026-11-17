package lms.api.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.api.identity.client.http.HttpAuthClient;
import lms.api.identity.client.http.HttpUserClient;
import lms.api.identity.dto.AuthDtos.SwitchTenantRequest;
import lms.api.support.ClientInfo;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Client identity cho phiên: đường dẫn, X-Client-Ip/X-Client-User-Agent chỉ ở các lời gọi phát token. */
class HttpSessionClientTest {

    static final UUID SID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID UID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    static final String TOKEN = "{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"tokenType\":\"Bearer\","
            + "\"expiresIn\":900,\"mustChangePassword\":false}";

    private record Fixture(MockRestServiceServer server, RestClient rc) {
        static Fixture create() {
            RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            return new Fixture(server, builder.build());
        }
    }

    @Test
    void guiIpVaUserAgentOCacLoiGoiPhatToken() {
        Fixture f = Fixture.create();
        for (String uri : new String[] {"login", "refresh", "switch-tenant", "external/exchange"}) {
            f.server.expect(once(), requestTo("http://identity/internal/auth/" + uri)).andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Client-Ip", "203.0.113.9"))
                    .andExpect(header("X-Client-User-Agent", "Mozilla/5.0 Test"))
                    .andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
        }
        HttpAuthClient client = new HttpAuthClient(f.rc, () -> new ClientInfo("203.0.113.9", "Mozilla/5.0 Test"));
        client.login("alice", "pw", null);
        client.refresh("r");
        client.switchTenant(new SwitchTenantRequest(UUID.randomUUID(), "r"));
        client.externalExchange("c");
        f.server.verify();
    }

    @Test
    void khongGuiHeaderKhiKhongCoThongTinVaKhongGuiOLogout() {
        Fixture f = Fixture.create();
        f.server.expect(requestTo("http://identity/internal/auth/login"))
                .andExpect(headerDoesNotExist("X-Client-Ip")).andExpect(headerDoesNotExist("X-Client-User-Agent"))
                .andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON));
        new HttpAuthClient(f.rc).login("alice", "pw", null);
        f.server.verify();

        Fixture g = Fixture.create();
        g.server.expect(requestTo("http://identity/internal/auth/logout"))
                .andExpect(headerDoesNotExist("X-Client-Ip")).andExpect(headerDoesNotExist("X-Client-User-Agent"))
                .andRespond(withNoContent());
        new HttpAuthClient(g.rc, () -> new ClientInfo("203.0.113.9", "UA")).logout("r");
        g.server.verify();
    }

    @Test
    void phienCuaToiGoiDungDuongDan() {
        Fixture f = Fixture.create();
        f.server.expect(requestTo("http://identity/internal/auth/sessions")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"sessionId\":\"" + SID + "\",\"channel\":\"STAFF\",\"tenantId\":null,"
                        + "\"tenantName\":null,\"deviceSummary\":\"Chrome trên Windows\",\"ipAddress\":\"1.2.3.4\","
                        + "\"startedAt\":\"2026-11-17T01:00:00Z\",\"lastUsedAt\":\"2026-11-17T02:00:00Z\","
                        + "\"expiresAt\":\"2026-11-24T02:00:00Z\",\"current\":true,\"extra\":1}]",
                        MediaType.APPLICATION_JSON));
        f.server.expect(requestTo("http://identity/internal/auth/sessions/" + SID)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        f.server.expect(requestTo("http://identity/internal/auth/sessions/revocation")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.keepCurrent").value(true))
                .andRespond(withSuccess("{\"revokedSessions\":3}", MediaType.APPLICATION_JSON));

        HttpAuthClient client = new HttpAuthClient(f.rc);
        var sessions = client.mySessions();
        assertEquals(1, sessions.size());
        assertEquals("1.2.3.4", sessions.get(0).ipAddress());
        assertEquals(true, sessions.get(0).current());
        client.revokeMySession(SID);
        assertEquals(3, client.revokeMySessions(true).revokedSessions());
        f.server.verify();
    }

    @Test
    void phienCuaNguoiDungGoiDungDuongDan() {
        Fixture f = Fixture.create();
        f.server.expect(requestTo("http://identity/internal/users/" + UID + "/sessions"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"sessionId\":\"" + SID + "\",\"channel\":\"STOREFRONT\","
                        + "\"deviceSummary\":\"Safari trên iOS\",\"ipAddress\":\"5.6.7.8\","
                        + "\"startedAt\":\"2026-11-17T01:00:00Z\",\"lastUsedAt\":\"2026-11-17T02:00:00Z\","
                        + "\"expiresAt\":\"2026-11-24T02:00:00Z\"}]", MediaType.APPLICATION_JSON));
        f.server.expect(requestTo("http://identity/internal/users/" + UID + "/sessions/" + SID))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());

        HttpUserClient client = new HttpUserClient(f.rc);
        assertEquals("5.6.7.8", client.listSessions(UID).get(0).ipAddress());
        client.revokeSession(UID, SID);
        f.server.verify();
    }
}
