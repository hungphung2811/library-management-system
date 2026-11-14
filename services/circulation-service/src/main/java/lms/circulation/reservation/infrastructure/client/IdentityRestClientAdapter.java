package lms.circulation.reservation.infrastructure.client;

import lms.circulation.reservation.application.ExternalServiceUnavailableException;
import lms.circulation.reservation.application.ReaderEligibilityPort;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Gọi GET /api/users/me/reader-profile của identity-service bằng token của người dùng. */
public class IdentityRestClientAdapter implements ReaderEligibilityPort {

    private final RestClient client;

    public IdentityRestClientAdapter(RestClient client) {
        this.client = client;
    }

    record ReaderProfileDto(UUID userId, boolean cardValid) {
    }

    @Override
    public ReaderEligibility check(UUID readerId) {
        String token = BearerTokens.current().orElseThrow(() ->
                new ExternalServiceUnavailableException("Thiếu access token để kiểm tra thẻ độc giả"));
        try {
            ReaderProfileDto dto = client.get().uri("/api/users/me/reader-profile")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().body(ReaderProfileDto.class);
            if (dto == null || !readerId.equals(dto.userId())) {
                return new ReaderEligibility(false, false);
            }
            return new ReaderEligibility(true, dto.cardValid());
        } catch (HttpClientErrorException.NotFound e) {
            return new ReaderEligibility(false, false);
        } catch (RestClientException e) {
            throw new ExternalServiceUnavailableException("identity-service", e);
        }
    }
}
