package lms.sales.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.infrastructure.client.HttpCatalogLookupAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Adapter gọi POST /internal/books/lookup của catalog: chia lô 100 id, chuyển token, lỗi thành CATALOG_UNAVAILABLE. */
class HttpCatalogLookupAdapterTest {

    private MockRestServiceServer server;
    private HttpCatalogLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog.test");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new HttpCatalogLookupAdapter(builder.build());
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void khongCoIdThiKhongGoiCatalog() {
        assertThat(adapter.lookup(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void traVeDauSachThayVaBoQuaIdKhongThay() {
        UUID found = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        server.expect(requestTo("http://catalog.test/internal/books/lookup")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ids.length()").value(2))
                .andRespond(withSuccess("[{\"id\":\"" + found + "\",\"name\":\"Clean Code\",\"author\":\"RM\","
                        + "\"isbn\":null,\"coverPrice\":250000}]", MediaType.APPLICATION_JSON));
        Map<UUID, CatalogTitle> result = adapter.lookup(List.of(found, missing, found));
        assertThat(result).containsOnlyKeys(found);
        assertThat(result.get(found).name()).isEqualTo("Clean Code");
        assertThat(result.get(found).coverPrice()).isEqualByComparingTo("250000");
        assertThat(result.get(found).isbn()).isNull();
        server.verify();
    }

    @Test
    void chiaLoMoiLanToiDa100Id() {
        List<UUID> ids = IntStream.range(0, 150).mapToObj(i -> UUID.randomUUID()).toList();
        server.expect(jsonPath("$.ids.length()").value(100)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(jsonPath("$.ids.length()").value(50)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(adapter.lookup(ids)).isEmpty();
        server.verify();
    }

    @Test
    void chuyenTiepTokenNguoiDung() {
        Jwt jwt = Jwt.withTokenValue("token-nguoi-dung").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        server.expect(header("Authorization", "Bearer token-nguoi-dung"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        adapter.lookup(List.of(UUID.randomUUID()));
        server.verify();
    }

    @Test
    void loiCatalogThanhCatalogUnavailable() {
        for (var response : List.of(withServerError(), withStatus(HttpStatus.FORBIDDEN),
                withStatus(HttpStatus.BAD_GATEWAY))) {
            server.reset();
            server.expect(requestTo("http://catalog.test/internal/books/lookup")).andRespond(response);
            assertThatThrownBy(() -> adapter.lookup(List.of(UUID.randomUUID())))
                    .isInstanceOfSatisfying(SalesException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.CATALOG_UNAVAILABLE));
        }
    }
}
