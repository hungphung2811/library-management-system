package lms.api.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.UUID;
import lms.api.support.client.http.HttpNotificationAdminClient;
import lms.api.support.dto.NotificationAdminDtos.ChannelItem;
import lms.api.support.dto.NotificationAdminDtos.PreferenceItem;
import lms.api.support.dto.NotificationAdminDtos.SaveTemplateRequest;
import lms.api.support.dto.NotificationAdminDtos.UpdateChannelsRequest;
import lms.api.support.dto.NotificationAdminDtos.UpdatePreferencesRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Máy khách gọi đúng đường dẫn nội bộ thông báo của support-service, chuyển tiếp token và bỏ qua trường lạ. */
class HttpNotificationAdminClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://support")
            .requestInterceptor(new TokenRelayInterceptor());
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpNotificationAdminClient client = new HttpNotificationAdminClient(builder.build());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login() {
        Jwt jwt = Jwt.withTokenValue("tok").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @Test
    void tuyChonVaLichSuCuaToi() {
        login();
        server.expect(requestTo("http://support/internal/notifications/me/preferences")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("[{\"category\":\"FINE\",\"channel\":\"EMAIL\",\"enabled\":true,\"mandatory\":false,\"moi\":1}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://support/internal/notifications/me/preferences")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"items\":[{\"category\":\"FINE\",\"channel\":\"EMAIL\",\"enabled\":false}]}"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://support/internal/notifications/me/deliveries")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertEquals("FINE", client.myPreferences().get(0).category());
        client.updateMyPreferences(new UpdatePreferencesRequest(List.of(new PreferenceItem("FINE", "EMAIL", false))));
        assertEquals(0, client.myDeliveries().size());
        server.verify();
    }

    @Test
    void lichSuGuiLocChiGuiThamSoCoGiaTriVaGuiLai() {
        login();
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://support/internal/notification-deliveries?page=1&size=5&status=DEAD&type=ORDER_PAID"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[],\"total\":3,\"page\":1,\"size\":5}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://support/internal/notification-deliveries/" + id + "/retry"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"status\":\"PENDING\",\"attempts\":6}",
                        MediaType.APPLICATION_JSON));

        assertEquals(3, client.searchDeliveries("DEAD", null, "ORDER_PAID", " ", null, 1, 5).total());
        assertEquals("PENDING", client.retryDelivery(id).status());
        server.verify();
    }

    @Test
    void cauHinhKenhVaMau() {
        login();
        server.expect(requestTo("http://support/internal/notification-settings/channels")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"items\":[{\"type\":\"ORDER_PAID\",\"channel\":\"EMAIL\",\"enabled\":false}]}"))
                .andRespond(withSuccess("[{\"type\":\"ORDER_PAID\",\"category\":\"ORDER\",\"channel\":\"EMAIL\",\"enabled\":false}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://support/internal/notification-templates/ORDER_PAID/EMAIL"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"subject\":\"S {{order_no}}\",\"body\":\"B\"}"))
                .andRespond(withSuccess("{\"type\":\"ORDER_PAID\",\"channel\":\"EMAIL\",\"source\":\"TENANT\",\"version\":2,"
                        + "\"subject\":\"S {{order_no}}\",\"body\":\"B\",\"allowedParams\":[\"order_no\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://support/internal/notification-templates/ORDER_PAID/EMAIL/preview"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"subject\":\"S\",\"body\":\"B\"}", MediaType.APPLICATION_JSON));

        assertEquals(false, client.updateChannelSettings(
                new UpdateChannelsRequest(List.of(new ChannelItem("ORDER_PAID", "EMAIL", false)))).get(0).enabled());
        assertEquals(2, client.saveTemplate("ORDER_PAID", "EMAIL", new SaveTemplateRequest("S {{order_no}}", "B")).version());
        assertEquals("S", client.previewTemplate("ORDER_PAID", "EMAIL",
                new lms.api.support.dto.NotificationAdminDtos.PreviewTemplateRequest(null, null)).subject());
        server.verify();
    }
}
