package lms.api.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.sales.client.SalesClient;
import lms.api.sales.client.http.HttpSalesClient;
import lms.api.sales.dto.SalesDtos.*;
import lms.api.sales.web.SalesContactRequestController;
import lms.api.support.TokenRelayInterceptor;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** T5 (ADR-0015): lms-api chuyển nguyên 5 endpoint liên hệ, 4xx chuyển nguyên, không lộ PII qua toString. */
class SalesContactRequestTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String B = "http://sales/internal/sales";

    static final String DETAIL = "{\"id\":\"" + ID + "\",\"topic\":\"ORDER\",\"status\":\"NEW\","
            + "\"createdAt\":\"2026-11-17T01:00:00Z\",\"closedAt\":null,"
            + "\"contact\":{\"name\":\"N***** V** B***\",\"email\":\"b***@example.vn\",\"phone\":\"*******678\"},"
            + "\"piiAnonymizedAt\":null,\"version\":0,\"message\":\"Noi dung\","
            + "\"customerUserId\":\"00000000-0000-0000-0000-0000000000c1\",\"tenantId\":\"khong-duoc-co\"}";

    // ---------------------------------------------------------------- client HTTP

    @Nested
    class Client {
        private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales")
                .requestInterceptor(new TokenRelayInterceptor());
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        private final HttpSalesClient client = new HttpSalesClient(builder.build());

        @AfterEach
        void clear() {
            SecurityContextHolder.clearContext();
        }

        private void login() {
            Jwt jwt = Jwt.withTokenValue("token-nv").header("alg", "none").subject("u1").build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        }

        @Test
        void namEndpointGoiDungDuongDanMethodVaChuyenTokenNhanVien() {
            login();
            server.expect(requestTo(B + "/contact-requests?status=NEW&topic=ORDER&from=2026-11-01T00:00:00Z"
                            + "&to=2026-12-01T00:00:00Z&page=1&size=20"))
                    .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer token-nv"))
                    .andRespond(withSuccess("{\"items\":[{\"id\":\"" + ID + "\",\"topic\":\"ORDER\",\"status\":\"NEW\","
                            + "\"createdAt\":\"2026-11-17T01:00:00Z\",\"contact\":{\"name\":\"N*\",\"email\":\"b***@x.vn\","
                            + "\"phone\":null},\"version\":0,\"campoLa\":1}],\"total\":1,\"page\":1,\"size\":20}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/contact-requests/" + ID)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(DETAIL, MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/contact-requests/" + ID + "/contact")).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"fullName\":\"Nguyen Van Binh\",\"email\":\"binh@example.vn\","
                            + "\"phone\":null,\"piiAnonymizedAt\":null}", MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/contact-requests/" + ID + "/status")).andExpect(method(HttpMethod.PUT))
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS")).andExpect(jsonPath("$.version").value(0))
                    .andRespond(withSuccess(DETAIL.replace("\"NEW\"", "\"IN_PROGRESS\""), MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/contact-requests/" + ID + "/anonymization"))
                    .andExpect(method(HttpMethod.POST)).andExpect(content().string(""))
                    .andRespond(withSuccess(DETAIL, MediaType.APPLICATION_JSON));

            var page = client.searchContactRequests(new ShopContactSearchQuery("NEW", "ORDER",
                    "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z", 1, 20));
            assertEquals(1, page.total());
            assertEquals("b***@x.vn", page.items().get(0).contact().email());
            assertEquals("Noi dung", client.getContactRequest(ID).message());
            assertEquals("binh@example.vn", client.getContactRequestContact(ID).email());
            assertEquals("IN_PROGRESS",
                    client.updateContactRequestStatus(ID, new UpdateShopContactStatusRequest("IN_PROGRESS", 0L))
                            .status());
            assertNull(client.anonymizeContactRequest(ID).piiAnonymizedAt());
            server.verify();
        }

        @Test
        void khongCoThamSoThiBoQuaVaLoi4xxCuaSalesDuocNemNguyen() {
            login();
            server.expect(requestTo(B + "/contact-requests")).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":0,\"size\":20}",
                            MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/contact-requests/" + ID)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"SHOP_CONTACT_NOT_FOUND\"}"));
            client.searchContactRequests(new ShopContactSearchQuery(null, " ", null, null, null, null));
            HttpClientErrorException ex = assertThrows(HttpClientErrorException.class,
                    () -> client.getContactRequest(ID));
            assertEquals(404, ex.getStatusCode().value());
            server.verify();
        }
    }

    // ---------------------------------------------------------------- toString che

    @Test
    void toStringCuaViewLienHeKhongLoPii() {
        var contact = new ShopContactContactView("Nguyen Van Binh", "binh@example.vn", "0912345678", null);
        var masked = new ShopContactMaskedContact("N*", "b***@example.vn", "*******678");
        var detail = new ShopContactDetailView(ID, "ORDER", "NEW", Instant.now(), null, masked, null, 0, "noi dung rieng",
                UUID.randomUUID());
        var summary = new ShopContactSummaryView(ID, "ORDER", "NEW", Instant.now(), null, masked, null, 0);
        for (Object o : List.of(contact, masked, detail, summary)) {
            assertThat(o.toString()).doesNotContain("binh@example.vn").doesNotContain("Nguyen Van Binh")
                    .doesNotContain("0912345678").doesNotContain("noi dung rieng").doesNotContain("b***");
        }
    }

    // ---------------------------------------------------------------- controller (routing)

    @Nested
    @WebMvcTest(SalesContactRequestController.class)
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    class Controller {
        @Autowired MockMvc mvc;
        @MockitoBean SalesClient sales;
        @MockitoBean JwtDecoder jwtDecoder;

        private ShopContactDetailView detail() {
            return new ShopContactDetailView(ID, "ORDER", "NEW", Instant.parse("2026-11-17T01:00:00Z"), null,
                    new ShopContactMaskedContact("N*", "b***@x.vn", null), null, 0, "m", UUID.randomUUID());
        }

        private static HttpClientErrorException downstream(HttpStatus status, String code) {
            String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}";
            return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY,
                    body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }

        @Test
        void khongTokenBi401KhongGoiClient() throws Exception {
            mvc.perform(get("/api/sales/contact-requests")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/sales/contact-requests/" + ID)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/sales/contact-requests/" + ID + "/contact")).andExpect(status().isUnauthorized());
            mvc.perform(put("/api/sales/contact-requests/" + ID + "/status")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/sales/contact-requests/" + ID + "/anonymization"))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(sales);
        }

        @Test
        void tokenKenhStorefrontBi401() throws Exception {
            var storefront = jwt().jwt(j -> j.tokenValue("t").audience(List.of("lms-storefront"))
                    .claim("tenantId", UUID.randomUUID().toString()));
            mvc.perform(get("/api/sales/contact-requests").with(storefront)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/sales/contact-requests/" + ID + "/contact").with(storefront))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(sales);
        }

        @Test
        void namEndpointChuyenXuongClientVoiThamSoDung() throws Exception {
            var token = jwt();
            when(sales.searchContactRequests(any())).thenReturn(new SalesPage<>(List.of(), 0, 0, 20));
            when(sales.getContactRequest(ID)).thenReturn(detail());
            when(sales.getContactRequestContact(ID))
                    .thenReturn(new ShopContactContactView("Nguyen", "a@x.vn", null, null));
            when(sales.updateContactRequestStatus(eq(ID), any())).thenReturn(detail());
            when(sales.anonymizeContactRequest(ID)).thenReturn(detail());

            mvc.perform(get("/api/sales/contact-requests?status=NEW&topic=PAYMENT&from=2026-11-01T00:00:00Z&page=2&size=5")
                    .with(token)).andExpect(status().isOk());
            ArgumentCaptor<ShopContactSearchQuery> q = ArgumentCaptor.forClass(ShopContactSearchQuery.class);
            verify(sales).searchContactRequests(q.capture());
            assertEquals(new ShopContactSearchQuery("NEW", "PAYMENT", "2026-11-01T00:00:00Z", null, 2, 5), q.getValue());

            mvc.perform(get("/api/sales/contact-requests/" + ID).with(token)).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("m")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contact.email").value("b***@x.vn"));
            mvc.perform(get("/api/sales/contact-requests/" + ID + "/contact").with(token)).andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.email").value("a@x.vn"));
            mvc.perform(put("/api/sales/contact-requests/" + ID + "/status").with(token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"version\":3}"))
                    .andExpect(status().isOk());
            ArgumentCaptor<UpdateShopContactStatusRequest> r = ArgumentCaptor
                    .forClass(UpdateShopContactStatusRequest.class);
            verify(sales).updateContactRequestStatus(eq(ID), r.capture());
            assertEquals("CLOSED", r.getValue().status());
            assertEquals(3L, r.getValue().version());
            mvc.perform(post("/api/sales/contact-requests/" + ID + "/anonymization").with(token))
                    .andExpect(status().isOk());
        }

        @Test
        void thieuTruongBatBuocCuaDoiTrangThaiLa400KhongGoiClient() throws Exception {
            var token = jwt();
            for (String body : List.of("{}", "{\"status\":\"CLOSED\"}", "{\"version\":1}",
                    "{\"status\":\"CLOSED\",\"version\":-1}")) {
                mvc.perform(put("/api/sales/contact-requests/" + ID + "/status").with(token)
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            }
            mvc.perform(get("/api/sales/contact-requests/khong-phai-uuid").with(token))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(sales);
        }

        @Test
        void loi4xxCuaSalesChuyenNguyenVa5xxThanh502() throws Exception {
            var token = jwt();
            when(sales.getContactRequest(ID)).thenThrow(downstream(HttpStatus.NOT_FOUND, "SHOP_CONTACT_NOT_FOUND"));
            mvc.perform(get("/api/sales/contact-requests/" + ID).with(token)).andExpect(status().isNotFound())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SHOP_CONTACT_NOT_FOUND"));
            doThrow(downstream(HttpStatus.CONFLICT, "SHOP_CONTACT_STATE_CONFLICT")).when(sales)
                    .updateContactRequestStatus(eq(ID), any());
            mvc.perform(put("/api/sales/contact-requests/" + ID + "/status").with(token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\",\"version\":3}"))
                    .andExpect(status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SHOP_CONTACT_STATE_CONFLICT"));
            when(sales.getContactRequestContact(ID)).thenThrow(downstream(HttpStatus.FORBIDDEN, "FORBIDDEN"));
            mvc.perform(get("/api/sales/contact-requests/" + ID + "/contact").with(token))
                    .andExpect(status().isForbidden());
            when(sales.anonymizeContactRequest(ID)).thenThrow(
                    org.springframework.web.client.HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                            "x", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
            mvc.perform(post("/api/sales/contact-requests/" + ID + "/anonymization").with(token))
                    .andExpect(status().isBadGateway());
        }
    }
}
