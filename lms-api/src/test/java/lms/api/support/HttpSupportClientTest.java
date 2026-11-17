package lms.api.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.api.support.client.http.HttpSupportClient;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Máy khách gọi đúng đường dẫn nội bộ /internal/audit-logs, ánh xạ DTO và chuyển tiếp token. */
class HttpSupportClientTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void goiApiNoiBoChuyenTiepTokenVaAnhXaTrang() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://support")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupportClient client = new HttpSupportClient(builder.build());

        Jwt jwt = Jwt.withTokenValue("token-nguoi-dung").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        server.expect(requestTo("http://support/internal/audit-logs?page=1&size=2&aggregateId=agg&eventType=E"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-nguoi-dung"))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"" + UUID.randomUUID() + "\",\"eventId\":\""
                        + UUID.randomUUID() + "\",\"eventType\":\"E\",\"aggregateId\":\"agg\","
                        + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"recordedAt\":\"2026-01-01T00:00:01Z\","
                        + "\"payload\":\"{}\",\"campoMoi\":1}],\"total\":7,\"page\":1,\"size\":2}",
                        MediaType.APPLICATION_JSON));

        var page = client.searchAuditLogs("agg", "E", null, 1, 2);
        assertEquals(7, page.total());
        assertEquals("E", page.items().get(0).eventType());
        server.verify();
    }

    @Test
    void thongBaoDocDemVaDanhDauGoiDungDuongDan() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://support")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSupportClient client = new HttpSupportClient(builder.build());
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        UUID id = UUID.randomUUID();

        server.expect(requestTo("http://support/internal/notifications/me/unread-count"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"unread\":6}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://support/internal/notifications/" + id + "/read"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"type\":\"T\",\"message\":\"m\","
                        + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"readAt\":\"2026-01-01T00:01:00Z\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://support/internal/notifications/read-all"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"updated\":2}", MediaType.APPLICATION_JSON));

        assertEquals(6, client.myUnreadNotificationCount().unread());
        var read = client.markNotificationRead(id);
        assertEquals(id, read.id());
        assertEquals(java.time.Instant.parse("2026-01-01T00:01:00Z"), read.readAt());
        assertEquals(2, client.markAllNotificationsRead().updated());
        server.verify();
    }
}
