package lms.customer.storefront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;
import java.util.UUID;
import lms.customer.storefront.client.http.HttpStorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpStorefrontAccessClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpStorefrontAccessClient client = new HttpStorefrontAccessClient(builder.build(), "bi-mat-rieng");

    @Test
    void guiBiMatRiengVaTenantCodeRoiAnhXaKetQua() {
        UUID tenantId = UUID.randomUUID();
        server.expect(requestTo("http://identity/internal/storefront/access-tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Storefront-Client-Secret", "bi-mat-rieng"))
                .andExpect(jsonPath("$.tenantCode").value("shop-a"))
                .andRespond(withSuccess("{\"tenantId\":\"" + tenantId + "\",\"code\":\"shop-a\",\"name\":\"Hiệu sách A\","
                        + "\"accessToken\":\"jwt\",\"expiresIn\":300,\"truongMoi\":1}", MediaType.APPLICATION_JSON));
        Optional<StorefrontAccess> result = client.issue("shop-a");
        assertTrue(result.isPresent());
        assertEquals(tenantId, result.get().tenantId());
        assertEquals("Hiệu sách A", result.get().name());
        assertEquals(300, result.get().expiresIn());
        server.verify();
    }

    @Test
    void notFoundLaKhongKhaDung() {
        server.expect(requestTo("http://identity/internal/storefront/access-tokens"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"SHOP_NOT_AVAILABLE\",\"message\":\"x\"}"));
        assertTrue(client.issue("shop-a").isEmpty());
    }

    @Test
    void loiKhacLa503ChungKhongLoChiTiet() {
        server.expect(requestTo("http://identity/internal/storefront/access-tokens"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        PublicApiException a = assertThrows(PublicApiException.class, () -> client.issue("shop-a"));
        assertEquals(503, a.status().value());

        server.reset();
        server.expect(requestTo("http://identity/internal/storefront/access-tokens")).andRespond(withServerError());
        PublicApiException b = assertThrows(PublicApiException.class, () -> client.issue("shop-a"));
        assertEquals("SERVICE_UNAVAILABLE", b.code());
    }
}
