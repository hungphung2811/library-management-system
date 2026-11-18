package lms.sales.stock.infrastructure.client;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi POST /internal/books/lookup của catalog-service bằng token người dùng chuyển tiếp (ADR-0010 mục 2.2), tối đa
 * 100 id mỗi lần. Id không thấy thì không có trong kết quả. Mọi lỗi gọi (kết nối, hết thời gian, 4xx/5xx kể cả 403
 * thiếu khóa lookup) thành SalesException(CATALOG_UNAVAILABLE): sales không bán/liệt kê khi không tra được catalog.
 */
public class HttpCatalogLookupAdapter implements CatalogLookupPort {

    static final int MAX_IDS_PER_CALL = 100;
    private static final Logger log = LoggerFactory.getLogger(HttpCatalogLookupAdapter.class);

    record TitleDto(UUID id, String name, String author, String isbn, BigDecimal coverPrice, String description,
                    boolean hasPreview, String subtitle, Integer publicationYear, String edition, String language,
                    Integer pageCount, String format, String translator, String series, Integer seriesNumber,
                    String coverImageUrl, List<String> tags) {
        CatalogTitle toTitle() {
            return new CatalogTitle(id, name, author, isbn, coverPrice, description, hasPreview, subtitle,
                    publicationYear, edition, language, pageCount, format, translator, series, seriesNumber,
                    coverImageUrl, tags);
        }
    }

    record PreviewDto(UUID id, String name, String previewText) {
    }

    record LookupRequest(List<UUID> ids) {
    }

    private final RestClient client;

    public HttpCatalogLookupAdapter(RestClient client) {
        this.client = client;
    }

    @Override
    public Map<UUID, CatalogTitle> lookup(Collection<UUID> titleIds) {
        Map<UUID, CatalogTitle> out = new HashMap<>();
        if (titleIds == null || titleIds.isEmpty()) {
            return out;
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(titleIds));
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_CALL) {
            List<UUID> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_CALL, ids.size()));
            for (TitleDto t : call(chunk)) {
                out.put(t.id(), t.toTitle());
            }
        }
        return out;
    }

    /** Tra theo từ khóa (tên/tác giả), catalog trả tối đa 200 đầu sách (ADR-0011 mục 2.6). */
    @Override
    public List<CatalogTitle> lookupByKeyword(String keyword) {
        try {
            RestClient.RequestHeadersSpec<?> spec = client.get()
                    .uri(u -> u.path("/internal/books/lookup").queryParam("keyword", keyword).build());
            addHeaders(spec);
            TitleDto[] body = spec.retrieve().body(TitleDto[].class);
            return body == null ? List.of() : java.util.Arrays.stream(body).map(TitleDto::toTitle).toList();
        } catch (RestClientException e) {
            log.warn("Không tra được catalog-service theo từ khóa: {}", e.getMessage());
            throw new SalesException(ErrorCode.CATALOG_UNAVAILABLE, "Không gọi được catalog-service");
        }
    }

    /** Mô tả và đoạn đọc thử; catalog trả 404 (đầu sách không thấy) thì rỗng, lỗi khác là CATALOG_UNAVAILABLE. */
    @Override
    public java.util.Optional<CatalogPreview> preview(UUID titleId) {
        try {
            RestClient.RequestHeadersSpec<?> spec = client.get().uri("/internal/books/{id}/preview", titleId);
            addHeaders(spec);
            PreviewDto body = spec.retrieve().body(PreviewDto.class);
            return body == null ? java.util.Optional.empty()
                    : java.util.Optional.of(new CatalogPreview(body.id(), body.name(), body.previewText()));
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        } catch (RestClientException e) {
            log.warn("Không tra được preview ở catalog-service: {}", e.getMessage());
            throw new SalesException(ErrorCode.CATALOG_UNAVAILABLE, "Không gọi được catalog-service");
        }
    }

    private static void addHeaders(RestClient.RequestHeadersSpec<?> spec) {
        bearerToken().ifPresent(t -> spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + t));
        lms.security.permission.ActAsTenant.current().ifPresent(tid -> spec.header(lms.security.permission.ActAsTenant.HEADER, tid));
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            spec.header("X-Correlation-Id", correlationId);
        }
    }

    private TitleDto[] call(List<UUID> ids) {
        try {
            RestClient.RequestBodySpec spec = client.post().uri("/internal/books/lookup");
            addHeaders(spec);
            TitleDto[] body = spec.body(new LookupRequest(List.copyOf(ids))).retrieve().body(TitleDto[].class);
            return body == null ? new TitleDto[0] : body;
        } catch (RestClientException e) {
            log.warn("Không tra được catalog-service: {}", e.getMessage());
            throw new SalesException(ErrorCode.CATALOG_UNAVAILABLE, "Không gọi được catalog-service");
        }
    }

    private static java.util.Optional<String> bearerToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof AbstractOAuth2TokenAuthenticationToken<?> token) {
            return java.util.Optional.of(token.getToken().getTokenValue());
        }
        return java.util.Optional.empty();
    }
}
