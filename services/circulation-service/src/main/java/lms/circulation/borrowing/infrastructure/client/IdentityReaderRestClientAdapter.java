package lms.circulation.borrowing.infrastructure.client;

import lms.circulation.borrowing.application.DependencyUnavailableException;
import lms.circulation.borrowing.application.ReaderDirectoryPort;
import lms.circulation.borrowing.domain.ReaderType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Gọi GET /internal/users/{id}/reader-profile của identity-service bằng token của thủ thư (cần quyền xem hồ sơ độc giả). */
public class IdentityReaderRestClientAdapter implements ReaderDirectoryPort {

    private final RestClient client;

    public IdentityReaderRestClientAdapter(RestClient client) {
        this.client = client;
    }

    record ReaderProfileDto(UUID userId, ReaderType readerType, boolean cardValid) {
    }

    @Override
    public Optional<ReaderInfo> find(UUID readerId) {
        String token = BearerTokens.current().orElseThrow(() ->
                new DependencyUnavailableException("Thiếu access token để tra hồ sơ độc giả"));
        try {
            ReaderProfileDto dto = client.get().uri("/internal/users/{id}/reader-profile", readerId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().body(ReaderProfileDto.class);
            return dto == null ? Optional.empty() : Optional.of(new ReaderInfo(dto.userId(), dto.readerType(), dto.cardValid()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new DependencyUnavailableException("identity-service từ chối tra hồ sơ độc giả (" + e.getStatusCode() + ")");
        } catch (RestClientException e) {
            throw new DependencyUnavailableException("identity-service", e);
        }
    }
}
