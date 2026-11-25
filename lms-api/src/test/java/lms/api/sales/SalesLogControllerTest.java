package lms.api.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.sales.client.SalesLogClient;
import lms.api.sales.client.http.HttpSalesLogClient;
import lms.api.sales.dto.SalesLogDtos.LogEventView;
import lms.api.sales.dto.SalesLogDtos.LogPage;
import lms.api.sales.dto.SalesLogDtos.LogQuery;
import lms.api.sales.web.SalesLogController;
import lms.api.support.TokenRelayInterceptor;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/** Hợp đồng FE của log vận hành (ADR-0029) ở lms-api: /api/sales/logs/integration và /api/sales/logs/system. */
@WebMvcTest(SalesLogController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
class SalesLogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SalesLogClient logs;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static LogPage page() {
        return new LogPage(List.of(new LogEventView(UUID.randomUUID(), Instant.parse("2026-11-19T01:00:00Z"),
                "INTEGRATION_OUTBOUND", "momo", "integration-call", "WARN", "create", "FAILURE", 502, 830L,
                "SERVER_ERROR", "SocketTimeoutException", null, "cid-1", null, "ORD*****1234", TENANT)), 1, 0, 20);
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/sales/logs/integration")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/sales/logs/system")).andExpect(status().isUnauthorized());
        verifyNoInteractions(logs);
    }

    @Test
    void chuyenBoLocSangClientVaTraNguyenTrang() throws Exception {
        when(logs.integrationLogs(any())).thenReturn(page());
        when(logs.systemLogs(any())).thenReturn(page());
        mvc.perform(get("/api/sales/logs/integration").queryParam("channel", "OUTBOUND").queryParam("provider", "momo")
                        .queryParam("anomalyOnly", "true").queryParam("from", "2026-11-01T00:00:00Z")
                        .queryParam("to", "2026-11-10T00:00:00Z").queryParam("page", "1").queryParam("size", "50").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].provider").value("momo"))
                .andExpect(jsonPath("$.items[0].httpStatus").value(502))
                .andExpect(jsonPath("$.items[0].tenantId").value(TENANT.toString()));
        ArgumentCaptor<LogQuery> q = ArgumentCaptor.forClass(LogQuery.class);
        verify(logs).integrationLogs(q.capture());
        assertEquals("OUTBOUND", q.getValue().channel());
        assertEquals(Boolean.TRUE, q.getValue().anomalyOnly());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), q.getValue().from());
        assertEquals(1, q.getValue().page());
        assertEquals(50, q.getValue().size());
        mvc.perform(get("/api/sales/logs/system").with(jwt())).andExpect(status().isOk());
        verify(logs).systemLogs(any());
    }

    @Test
    void clientGoiDungDuongDanChuyenTiepTokenVaBoQuaThamSoRong() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://sales")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSalesLogClient client = new HttpSalesLogClient(builder.build());
        Jwt jwt = Jwt.withTokenValue("token-nv").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        String body = "{\"items\":[{\"id\":\"" + UUID.randomUUID() + "\",\"channel\":\"SYSTEM\",\"event\":\"system-event\","
                + "\"level\":\"WARN\",\"truongMoi\":1}],\"total\":1,\"page\":0,\"size\":20}";

        server.expect(requestTo("http://sales/internal/sales/logs/integration?channel=INBOUND&anomalyOnly=true&page=2"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer token-nv"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/logs/system?level=ERROR"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        LogPage a = client.integrationLogs(new LogQuery("INBOUND", " ", null, null, true, null, null, null, 2, null));
        assertEquals(1, a.total());
        assertEquals("SYSTEM", a.items().get(0).channel());
        client.systemLogs(new LogQuery(null, null, null, "ERROR", null, null, null, null, null, null));
        server.verify();
    }
}
