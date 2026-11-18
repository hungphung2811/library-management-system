package lms.customer.author;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import lms.customer.author.client.http.HttpAuthorCatalogClient;
import lms.customer.author.client.http.HttpTopSoldClient;
import lms.customer.author.dto.AuthorDtos.TopSoldItem;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class HttpAuthorClientsTest {

    private final RestClient.Builder catalogBuilder = RestClient.builder().baseUrl("http://catalog");
    private final MockRestServiceServer catalogServer = MockRestServiceServer.bindTo(catalogBuilder).build();
    private final HttpAuthorCatalogClient catalog = new HttpAuthorCatalogClient(catalogBuilder.build());

    private final RestClient.Builder salesBuilder = RestClient.builder().baseUrl("http://sales");
    private final MockRestServiceServer salesServer = MockRestServiceServer.bindTo(salesBuilder).build();
    private final HttpTopSoldClient sales = new HttpTopSoldClient(salesBuilder.build());

    @Test
    void newAuthorsGuiTokenDaysLimitVaBoTenTrong() {
        catalogServer.expect(requestTo("http://catalog/internal/books/new-authors?days=90&limit=200"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess("[\"An\",\"\",\"Binh\"]", MediaType.APPLICATION_JSON));
        assertThat(catalog.newAuthors("svc", 90, 200)).containsExactly("An", "Binh");
        catalogServer.verify();
    }

    @Test
    void authorsByTitleMotLoiGoiLoChiDocIdVaAuthorBoQuaTruongLa() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer svc"))
                .andExpect(content().json("{\"ids\":[\"" + a + "\",\"" + b + "\",\"" + c + "\"]}"))
                .andRespond(withSuccess("[{\"id\":\"" + a + "\",\"name\":\"S\",\"author\":\"An\",\"isbn\":\"1\","
                        + "\"coverPrice\":9},{\"id\":\"" + b + "\",\"author\":\" \"}]", MediaType.APPLICATION_JSON));
        Map<UUID, String> out = catalog.authorsByTitle("svc", List.of(a, b, c, a));
        assertThat(out).containsOnlyKeys(a).containsEntry(a, "An");
        catalogServer.verify();
    }

    @Test
    void authorsByTitleChiaLo100Id() {
        List<UUID> ids = IntStream.range(0, 150).mapToObj(i -> UUID.randomUUID()).toList();
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        catalogServer.expect(requestTo("http://catalog/internal/books/lookup"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(catalog.authorsByTitle("svc", ids)).isEmpty();
        catalogServer.verify();
    }

    @Test
    void authorsByTitleRongKhongGoiCatalog() {
        assertThat(catalog.authorsByTitle("svc", List.of())).isEmpty();
        catalogServer.verify();
    }

    @Test
    void topSoldChiDocTitleIdVaQuantity() {
        UUID id = UUID.randomUUID();
        salesServer.expect(requestTo("http://sales/internal/sales/storefront/top-sold?days=90&limit=100"))
                .andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess("[{\"titleId\":\"" + id + "\",\"quantity\":7,\"revenue\":123}]",
                        MediaType.APPLICATION_JSON));
        assertThat(sales.topSold("svc", 90, 100)).containsExactly(new TopSoldItem(id, 7));
        salesServer.verify();
    }

    @Test
    void salesTuChoiTokenThanh503Chung() {
        salesServer.expect(requestTo("http://sales/internal/sales/storefront/top-sold?days=90&limit=100"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> sales.topSold("svc", 90, 100)).isInstanceOf(PublicApiException.class);
    }

    @Test
    void loi5xxDuocNemDeHandlerDoiThanh503() {
        catalogServer.expect(requestTo("http://catalog/internal/books/new-authors?days=90&limit=200"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> catalog.newAuthors("svc", 90, 200)).isInstanceOf(HttpServerErrorException.class);
    }
}
