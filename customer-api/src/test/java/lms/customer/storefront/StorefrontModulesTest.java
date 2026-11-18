package lms.customer.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.customer.storefront.client.http.HttpStorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Module của tenant (ADR-0023 2.1): vắng modules = chỉ SALES, kiểm theo nhóm Feature, cache giữ nguyên hành vi. */
class StorefrontModulesTest {

    private final UUID tenantId = UUID.randomUUID();

    private StorefrontAccess access(Set<String> modules) {
        return new StorefrontAccess(tenantId, "shop-a", "A", "svc", 300, null, modules);
    }

    @Test
    void vangModulesCoiNhuChiSalesDeTrienKhaiCustomerApiTruocIdentity() {
        StorefrontAccess legacy = new StorefrontAccess(tenantId, "shop-a", "A", "svc", 300);
        assertThat(legacy.modules()).containsExactly("SALES");
        assertThat(legacy.supports(Feature.SHOP)).isTrue();
        assertThat(legacy.supports(Feature.LIBRARY)).isFalse();
        assertThat(legacy.supports(Feature.ANY)).isTrue();
    }

    @Test
    void kiemModuleTheoNhomRoute() {
        StorefrontAccess lendingOnly = access(Set.of("lending"));
        assertThat(lendingOnly.modules()).containsExactly("LENDING");
        assertThat(lendingOnly.supports(Feature.LIBRARY)).isTrue();
        assertThat(lendingOnly.supports(Feature.SHOP)).isFalse();
        assertThatThrownBy(() -> lendingOnly.require(Feature.SHOP)).isInstanceOf(PublicApiException.class)
                .satisfies(ex -> {
                    assertThat(((PublicApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(((PublicApiException) ex).code()).isEqualTo("SHOP_NOT_AVAILABLE");
                });
        assertThat(lendingOnly.require(Feature.LIBRARY)).isSameAs(lendingOnly);
        assertThat(access(Set.of("SALES", "LENDING")).supports(Feature.SHOP)).isTrue();
        assertThat(access(Set.of("SALES", "LENDING")).libraryEnabled()).isTrue();
        assertThat(access(Set.of("BOOKSTORE_X")).supports(Feature.ANY)).isFalse();
    }

    @Test
    void docModulesTuPhanHoiIdentity() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpStorefrontAccessClient client = new HttpStorefrontAccessClient(builder.build(), "bi-mat");
        server.expect(requestTo("http://identity/internal/storefront/access-tokens"))
                .andRespond(withSuccess("{\"tenantId\":\"" + tenantId + "\",\"code\":\"lib-a\",\"name\":\"Thư viện A\","
                        + "\"accessToken\":\"jwt\",\"expiresIn\":300,\"modules\":[\"LENDING\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://identity/internal/storefront/access-tokens"))
                .andRespond(withSuccess("{\"tenantId\":\"" + tenantId + "\",\"code\":\"shop-a\",\"name\":\"A\","
                        + "\"accessToken\":\"jwt\",\"expiresIn\":300}", MediaType.APPLICATION_JSON));
        assertThat(client.issue("lib-a").orElseThrow().modules()).containsExactly("LENDING");
        assertThat(client.issue("shop-a").orElseThrow().modules()).containsExactly("SALES");
        server.verify();
    }

    @Test
    void cacheGiuNguyenTtlVaTraModulesTheoMotLanGoi() {
        int[] calls = {0};
        StorefrontAccessCache cache = new StorefrontAccessCache(code -> {
            calls[0]++;
            return Optional.of(access(Set.of("LENDING")));
        }, 100, Duration.ofSeconds(60), Duration.ofSeconds(60));
        assertThat(cache.resolve("shop-a").libraryEnabled()).isTrue();
        assertThat(cache.resolve("shop-a").shopEnabled()).isFalse();
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void ketQuaKhongCoModuleCongKhaiNaoLaKhongKhaDung() {
        StorefrontAccessCache cache = new StorefrontAccessCache(code -> Optional.of(access(Set.of())), 100,
                Duration.ofSeconds(60), Duration.ofSeconds(60));
        assertThatThrownBy(() -> cache.resolve("shop-a")).isInstanceOf(PublicApiException.class);
    }
}
