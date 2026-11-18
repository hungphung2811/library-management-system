package lms.customer.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lms.customer.banner.client.http.HttpBannerClient;
import lms.customer.banner.dto.BannerDtos.BannerView;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpBannerClientTest {

    private final UUID id = UUID.randomUUID();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpBannerClient client = new HttpBannerClient(builder.build());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void guiTokenDichVuAnhXaTungTruongVaBoLinkNguyHiem() throws Exception {
        server.expect(requestTo("http://sales/internal/sales/storefront/banners"))
                .andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess("[{\"id\":\"" + id + "\",\"title\":\"A\",\"subtitle\":null,"
                        + "\"linkPath\":\"/books?sort=newest\",\"sortOrder\":1,\"tenantId\":\"x\",\"active\":true},"
                        + "{\"id\":\"" + id + "\",\"title\":\"B\",\"linkPath\":\"https://evil.test\",\"sortOrder\":2},"
                        + "{\"id\":\"" + id + "\",\"title\":\"C\",\"linkPath\":\"//evil.test\",\"sortOrder\":3},"
                        + "{\"id\":\"" + id + "\",\"title\":\"D\",\"linkPath\":\"javascript:alert(1)\",\"sortOrder\":4}]",
                        MediaType.APPLICATION_JSON));
        List<BannerView> out = client.list("svc");
        assertThat(out).extracting(BannerView::linkPath).containsExactly("/books?sort=newest", null, null, null);
        assertThat(json.writeValueAsString(out)).doesNotContain("tenantId", "active");
        server.verify();
    }

    @Test
    void chiGiuToiDa10Banner() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"id\":\"").append(UUID.randomUUID()).append("\",\"title\":\"t\",\"sortOrder\":")
                    .append(i).append("}");
        }
        server.expect(requestTo("http://sales/internal/sales/storefront/banners"))
                .andRespond(withSuccess(sb.append("]").toString(), MediaType.APPLICATION_JSON));
        assertEquals(10, client.list("svc").size());
    }

    @Test
    void salesTuChoiToken503() {
        server.expect(requestTo("http://sales/internal/sales/storefront/banners"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\"}"));
        PublicApiException ex = assertThrows(PublicApiException.class, () -> client.list("svc"));
        assertEquals(503, ex.status().value());
    }

    @Test
    void tapTenTruongBannerView() {
        assertThat(Arrays.stream(BannerView.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("id", "title", "subtitle", "linkPath", "sortOrder");
    }

    @Test
    void cacheGiu30GiayTheoTenantDemCaRongKhongDemLoi() {
        AtomicLong nanos = new AtomicLong();
        BannerCache cache = new BannerCache(Duration.ofSeconds(30), 100, nanos::get);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        cache.get(a, () -> {
            calls.incrementAndGet();
            return List.of();
        });
        cache.get(a, () -> {
            calls.incrementAndGet();
            return List.of();
        });
        assertEquals(1, calls.get());
        cache.get(b, () -> {
            calls.incrementAndGet();
            return List.of();
        });
        assertEquals(2, calls.get());
        nanos.addAndGet(Duration.ofSeconds(31).toNanos());
        cache.get(a, () -> {
            calls.incrementAndGet();
            return List.of();
        });
        assertEquals(3, calls.get());

        UUID c = UUID.randomUUID();
        assertThrows(PublicApiException.class, () -> cache.get(c, () -> {
            throw PublicApiException.serviceUnavailable();
        }));
        cache.get(c, () -> {
            calls.incrementAndGet();
            return List.of();
        });
        assertEquals(4, calls.get());
    }
}
