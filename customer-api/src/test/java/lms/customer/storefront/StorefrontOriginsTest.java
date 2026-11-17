package lms.customer.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.customer.storefront.client.http.HttpStorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Hợp đồng allowedOrigins của identity (ADR-0013 2.3): đọc khoan dung, chuẩn hóa chữ thường, tryResolve không ném. */
class StorefrontOriginsTest {

    private static HttpStorefrontAccessClient clientReplying(String body) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://identity/internal/storefront/access-tokens"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return new HttpStorefrontAccessClient(builder.build(), "bi-mat");
    }

    @Test
    void identityCuThieuTruongAllowedOriginsThiCoiLaRong() {
        UUID tenant = UUID.randomUUID();
        Optional<StorefrontAccess> access = clientReplying("{\"tenantId\":\"" + tenant
                + "\",\"code\":\"shop-a\",\"name\":\"A\",\"accessToken\":\"t\",\"expiresIn\":300}").issue("shop-a");
        assertThat(access).isPresent();
        assertThat(access.get().allowedOrigins()).isEmpty();
        assertThat(access.get().allowsOrigin("https://shop-a.test")).isFalse();
    }

    @Test
    void allowedOriginsNullHoacCoPhanTuRacDuocChuanHoa() {
        UUID tenant = UUID.randomUUID();
        StorefrontAccess withNull = clientReplying("{\"tenantId\":\"" + tenant
                + "\",\"code\":\"a\",\"name\":\"A\",\"accessToken\":\"t\",\"expiresIn\":300,\"allowedOrigins\":null}")
                .issue("a").orElseThrow();
        assertThat(withNull.allowedOrigins()).isEmpty();
        StorefrontAccess mixed = clientReplying("{\"tenantId\":\"" + tenant
                + "\",\"code\":\"a\",\"name\":\"A\",\"accessToken\":\"t\",\"expiresIn\":300,"
                + "\"allowedOrigins\":[\"HTTPS://Shop-A.test\",\"\",null,\"https://b.test\"]}").issue("a").orElseThrow();
        assertThat(mixed.allowedOrigins()).containsExactlyInAnyOrder("https://shop-a.test", "https://b.test");
        assertThat(mixed.allowsOrigin("https://shop-a.test")).isTrue();
    }

    @Test
    void tenantOriginsKhongBaoGioRaToString() {
        StorefrontAccess access = new StorefrontAccess(UUID.randomUUID(), "shop-a", "A", "tok-bi-mat", 300,
                Set.of("https://shop-a.test"));
        assertThat(access.toString()).doesNotContain("tok-bi-mat");
    }

    @Test
    void tryResolveKhongNemVaCungKetQuaChoMoiLyDoKhongKhaDung() {
        StorefrontAccessCache cache = new StorefrontAccessCache(code -> {
            if (code.equals("loi")) {
                throw PublicApiException.serviceUnavailable();
            }
            return Optional.empty();
        }, 10, java.time.Duration.ofSeconds(60), java.time.Duration.ofSeconds(60));
        assertThat(cache.tryResolve("khong-co")).isEmpty();
        assertThat(cache.tryResolve("loi")).isEmpty();
        assertThat(cache.tryResolve("Sai Dinh Dang!")).isEmpty();
        assertThat(cache.tryResolve(null)).isEmpty();
    }
}
