package lms.circulation.reservation.infrastructure.client;

import lms.circulation.reservation.application.ExternalServiceUnavailableException;
import lms.circulation.reservation.application.TitleCatalogPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Gọi GET /internal/books/{id} của catalog-service bằng token của người dùng (cần permission BOOK_VIEW). */
public class CatalogRestClientAdapter implements TitleCatalogPort {

    private final RestClient client;

    public CatalogRestClientAdapter(RestClient client) {
        this.client = client;
    }

    record TitleDto(UUID id, String name, long availableCopies) {
    }

    @Override
    public Optional<TitleInfo> findTitle(UUID titleId) {
        try {
            RestClient.RequestHeadersSpec<?> request = client.get().uri("/internal/books/{id}", titleId);
            BearerTokens.current().ifPresent(t -> request.header(HttpHeaders.AUTHORIZATION, "Bearer " + t));
            TitleDto dto = request.retrieve().body(TitleDto.class);
            return dto == null ? Optional.empty() : Optional.of(new TitleInfo(dto.id(), dto.name(), dto.availableCopies()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ExternalServiceUnavailableException("catalog-service", e);
        }
    }
}
