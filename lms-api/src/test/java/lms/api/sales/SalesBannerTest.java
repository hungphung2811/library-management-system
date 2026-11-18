package lms.api.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import lms.api.sales.dto.SalesDtos.BannerAdminView;
import lms.api.sales.dto.SalesDtos.SaveBannerRequest;
import lms.api.sales.web.SalesBannerController;
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** B5 (ADR-0019): lms-api chuyển nguyên 5 endpoint quản trị banner sang /internal/sales/banners. */
class SalesBannerTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String B = "http://sales/internal/sales";

    static final String VIEW = "{\"id\":\"" + ID + "\",\"title\":\"Giam 20%\",\"subtitle\":null,"
            + "\"linkPath\":\"/books?sort=newest\",\"sortOrder\":1,\"active\":true,"
            + "\"startsAt\":\"2026-11-20T00:00:00Z\",\"endsAt\":null,\"status\":\"SCHEDULED\",\"version\":3,"
            + "\"createdAt\":\"2026-11-17T01:00:00Z\",\"updatedAt\":\"2026-11-17T01:00:00Z\",\"campoLa\":1}";

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
            server.expect(requestTo(B + "/banners")).andExpect(method(HttpMethod.GET))
                    .andExpect(header("Authorization", "Bearer token-nv"))
                    .andRespond(withSuccess("[" + VIEW + "]", MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/banners/" + ID)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(VIEW, MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/banners")).andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.title").value("Giam 20%"))
                    .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(VIEW));
            server.expect(requestTo(B + "/banners/" + ID)).andExpect(method(HttpMethod.PUT))
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.startsAt").value("2026-11-20T00:00:00Z"))
                    .andRespond(withSuccess(VIEW, MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/banners/" + ID)).andExpect(method(HttpMethod.DELETE))
                    .andExpect(header("Authorization", "Bearer token-nv")).andExpect(content().string(""))
                    .andRespond(withNoContent());

            var req = new SaveBannerRequest("Giam 20%", null, "/books", 1, true,
                    Instant.parse("2026-11-20T00:00:00Z"), null, 3L);
            var list = client.listBanners();
            assertEquals(1, list.size());
            assertEquals("SCHEDULED", list.get(0).status());
            assertEquals(3L, client.getBanner(ID).version());
            assertEquals(ID, client.createBanner(req).id());
            assertEquals("Giam 20%", client.updateBanner(ID, req).title());
            client.deleteBanner(ID);
            server.verify();
        }

        @Test
        void loi4xxCuaSalesDuocNemNguyenVaDanhSachRongKhiKhongCoThan() {
            login();
            server.expect(requestTo(B + "/banners")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            server.expect(requestTo(B + "/banners/" + ID)).andRespond(withStatus(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"BANNER_VERSION_CONFLICT\"}"));
            assertThat(client.listBanners()).isEmpty();
            HttpClientErrorException ex = assertThrows(HttpClientErrorException.class,
                    () -> client.updateBanner(ID, new SaveBannerRequest("t", null, null, null, null, null, null, 1L)));
            assertEquals(409, ex.getStatusCode().value());
            server.verify();
        }
    }

    @Nested
    @WebMvcTest(SalesBannerController.class)
    @Import({SecurityConfiguration.class, ApiExceptionHandler.class})
    class Controller {
        @Autowired MockMvc mvc;
        @MockitoBean SalesClient sales;
        @MockitoBean JwtDecoder jwtDecoder;

        private BannerAdminView view() {
            return new BannerAdminView(ID, "Giam 20%", null, "/books", 1, true, null, null, "LIVE", 3,
                    Instant.parse("2026-11-17T01:00:00Z"), Instant.parse("2026-11-17T01:00:00Z"));
        }

        private static HttpClientErrorException downstream(HttpStatus status, String code) {
            String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}";
            return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY,
                    body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }

        @Test
        void khongTokenBi401KhongGoiClient() throws Exception {
            mvc.perform(get("/api/sales/banners")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/sales/banners/" + ID)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/sales/banners")).andExpect(status().isUnauthorized());
            mvc.perform(put("/api/sales/banners/" + ID)).andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/sales/banners/" + ID)).andExpect(status().isUnauthorized());
            verifyNoInteractions(sales);
        }

        @Test
        void tokenKenhStorefrontBi401() throws Exception {
            var storefront = jwt().jwt(j -> j.tokenValue("t").audience(List.of("lms-storefront"))
                    .claim("tenantId", UUID.randomUUID().toString()));
            mvc.perform(get("/api/sales/banners").with(storefront)).andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/sales/banners/" + ID).with(storefront)).andExpect(status().isUnauthorized());
            verifyNoInteractions(sales);
        }

        @Test
        void namEndpointChuyenXuongClientVoiThamSoDung() throws Exception {
            var token = jwt();
            when(sales.listBanners()).thenReturn(List.of(view()));
            when(sales.getBanner(ID)).thenReturn(view());
            when(sales.createBanner(any())).thenReturn(view());
            when(sales.updateBanner(eq(ID), any())).thenReturn(view());

            mvc.perform(get("/api/sales/banners").with(token)).andExpect(status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$[0].status").value("LIVE"));
            mvc.perform(get("/api/sales/banners/" + ID).with(token)).andExpect(status().isOk())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.version").value(3));
            mvc.perform(post("/api/sales/banners").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"A\",\"sortOrder\":2,\"active\":false,\"startsAt\":\"2026-11-20T00:00:00+07:00\"}"))
                    .andExpect(status().isCreated());
            ArgumentCaptor<SaveBannerRequest> c = ArgumentCaptor.forClass(SaveBannerRequest.class);
            verify(sales).createBanner(c.capture());
            assertEquals("A", c.getValue().title());
            assertEquals(2, c.getValue().sortOrder());
            assertEquals(false, c.getValue().active());
            assertEquals(Instant.parse("2026-11-19T17:00:00Z"), c.getValue().startsAt());

            mvc.perform(put("/api/sales/banners/" + ID).with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"B\",\"version\":3}")).andExpect(status().isOk());
            ArgumentCaptor<SaveBannerRequest> u = ArgumentCaptor.forClass(SaveBannerRequest.class);
            verify(sales).updateBanner(eq(ID), u.capture());
            assertEquals(3L, u.getValue().version());

            mvc.perform(delete("/api/sales/banners/" + ID).with(token)).andExpect(status().isNoContent());
            verify(sales).deleteBanner(ID);
        }

        @Test
        void idSaiKieuLa400KhongGoiClient() throws Exception {
            mvc.perform(get("/api/sales/banners/khong-phai-uuid").with(jwt())).andExpect(status().isBadRequest());
            verifyNoInteractions(sales);
        }

        @Test
        void loi4xxCuaSalesChuyenNguyen() throws Exception {
            var token = jwt();
            when(sales.getBanner(ID)).thenThrow(downstream(HttpStatus.NOT_FOUND, "BANNER_NOT_FOUND"));
            mvc.perform(get("/api/sales/banners/" + ID).with(token)).andExpect(status().isNotFound())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("BANNER_NOT_FOUND"));
            when(sales.createBanner(any())).thenThrow(downstream(HttpStatus.CONFLICT, "BANNER_LIMIT_EXCEEDED"));
            mvc.perform(post("/api/sales/banners").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"A\"}")).andExpect(status().isConflict())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("BANNER_LIMIT_EXCEEDED"));
            when(sales.updateBanner(eq(ID), any())).thenThrow(downstream(HttpStatus.BAD_REQUEST, "INVALID_BANNER"));
            mvc.perform(put("/api/sales/banners/" + ID).with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"\",\"version\":1}")).andExpect(status().isBadRequest())
                    .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("INVALID_BANNER"));
        }
    }
}
