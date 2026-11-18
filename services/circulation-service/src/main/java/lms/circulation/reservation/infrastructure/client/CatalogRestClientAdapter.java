package lms.circulation.reservation.infrastructure.client;

import lms.circulation.reservation.application.ExternalServiceUnavailableException;
import lms.circulation.reservation.application.TitleCatalogPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi GET /internal/books/{id} của catalog-service bằng token của người dùng (cần permission BOOK_VIEW). Tenant đi theo
 * token (claim tenantId): catalog chỉ trả dữ liệu tenant của token, đầu sách thuộc tenant khác thành 404 (Review Focus 6).
 * Không tự thêm header tenant nào khác token.
 */
public class CatalogRestClientAdapter implements TitleCatalogPort {

    private final RestClient client;

    public CatalogRestClientAdapter(RestClient client) {
        this.client = client;
    }

    record TitleDto(UUID id, String name, long availableCopies, UUID workId, String language, Long workAvailableCopies) {
    }

    @Override
    public Optional<TitleInfo> findTitle(UUID titleId) {
        try {
            RestClient.RequestHeadersSpec<?> request = client.get().uri("/internal/books/{id}", titleId);
            BearerTokens.current().ifPresent(t -> request.header(HttpHeaders.AUTHORIZATION, "Bearer " + t));
            lms.security.permission.ActAsTenant.current().ifPresent(tid -> request.header(lms.security.permission.ActAsTenant.HEADER, tid));
            TitleDto dto = request.retrieve().body(TitleDto.class);
            return dto == null ? Optional.empty() : Optional.of(new TitleInfo(dto.id(), dto.name(), dto.availableCopies(),
                    dto.workId(), dto.language(), dto.workAvailableCopies()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ExternalServiceUnavailableException("catalog-service", e);
        }
    }
}
