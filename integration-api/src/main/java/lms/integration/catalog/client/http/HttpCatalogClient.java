package lms.integration.catalog.client.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.integration.catalog.client.CatalogClient;
import lms.integration.catalog.dto.BookDtos.AuthorView;
import lms.integration.catalog.dto.BookDtos.BookView;
import lms.integration.config.DownstreamRestClients;
import lms.integration.web.IntegrationApiException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Gọi catalog-service {@code /internal/books/**} bằng token integration chuyển tiếp. Chỉ nhận các trường allowlist
 * ({@code @JsonIgnoreProperties(ignoreUnknown)} bỏ phần còn lại, nên tenantId, số bản sao... không bao giờ vào
 * {@link BookView}).
 */
public class HttpCatalogClient implements CatalogClient {

    private static final String BOOKS = "/internal/books";

    private final RestClient client;

    public HttpCatalogClient(RestClient client) {
        this.client = client;
    }

    @Override
    public BookView get(UUID id) {
        TitleDetail t = DownstreamRestClients.call(() -> client.get().uri(BOOKS + "/{id}", id)
                .accept(MediaType.APPLICATION_JSON).retrieve().body(TitleDetail.class));
        if (t == null) throw IntegrationApiException.serviceUnavailable();
        return t.toView();
    }

    @Override
    public List<BookView> lookup(List<UUID> ids) {
        return post("/lookup", Map.of("ids", ids));
    }

    @Override
    public List<BookView> lookupByIsbn(List<String> isbns) {
        return post("/lookup-by-isbn", Map.of("isbns", isbns));
    }

    private List<BookView> post(String path, Map<String, ?> body) {
        List<TitleLookup> rows = DownstreamRestClients.call(() -> client.post().uri(BOOKS + path)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(new ParameterizedTypeReference<List<TitleLookup>>() { }));
        return rows == null ? List.of() : rows.stream().map(TitleLookup::toView).toList();
    }

    /** Phần dùng của {@code TitleResponse}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TitleDetail(UUID id, String name, String subtitle, String author, List<AuthorView> authors, String isbn,
                       BigDecimal coverPrice, UUID publisherId, String publisherName, UUID categoryId,
                       String categoryName, Integer publicationYear, String edition, String language,
                       Integer pageCount, String format, String translator, String series, Integer seriesNumber,
                       String coverImageUrl, List<String> tags, UUID workId, String workTitle, Integer partNumber,
                       String partLabel) {
        BookView toView() {
            return new BookView(id, name, subtitle, author, authors == null ? List.of() : authors, isbn, coverPrice,
                    null, publisherId, publisherName, categoryId, categoryName, publicationYear, edition, language,
                    pageCount, format, translator, series, seriesNumber, coverImageUrl,
                    tags == null ? List.of() : tags, workId, workTitle, partNumber, partLabel);
        }
    }

    /** Phần dùng của {@code TitleLookupView}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TitleLookup(UUID id, String name, String subtitle, String author, String isbn, BigDecimal coverPrice,
                       String description, Integer publicationYear, String edition, String language,
                       Integer pageCount, String format, String translator, String series, Integer seriesNumber,
                       String coverImageUrl, List<String> tags, UUID workId, String workTitle, Integer partNumber,
                       String partLabel) {
        BookView toView() {
            return new BookView(id, name, subtitle, author, null, isbn, coverPrice, description, null, null, null,
                    null, publicationYear, edition, language, pageCount, format, translator, series, seriesNumber,
                    coverImageUrl, tags == null ? List.of() : tags, workId, workTitle, partNumber, partLabel);
        }
    }
}
