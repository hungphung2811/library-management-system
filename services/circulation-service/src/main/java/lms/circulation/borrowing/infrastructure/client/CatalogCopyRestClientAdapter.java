package lms.circulation.borrowing.infrastructure.client;

import lms.circulation.borrowing.application.CopyInventoryPort;
import lms.circulation.borrowing.application.DependencyUnavailableException;
import lms.circulation.borrowing.domain.BorrowingRejectedException;
import lms.circulation.borrowing.domain.CopyStatus;
import lms.circulation.borrowing.domain.NotFoundException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Gọi /internal/book-copies của catalog-service bằng token của thủ thư: tra bản sao và đổi trạng thái (idempotent). */
public class CatalogCopyRestClientAdapter implements CopyInventoryPort {

    private final RestClient client;

    public CatalogCopyRestClientAdapter(RestClient client) {
        this.client = client;
    }

    record CopyDto(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status, BigDecimal coverPrice) {
    }

    @Override
    public Optional<CopyInfo> findById(UUID copyId) {
        return fetch(() -> client.get().uri("/internal/book-copies/{id}", copyId));
    }

    @Override
    public Optional<CopyInfo> findByBarcode(String barcode) {
        return fetch(() -> client.get().uri("/internal/book-copies/by-barcode/{barcode}", barcode));
    }

    private Optional<CopyInfo> fetch(Supplier<RestClient.RequestHeadersSpec<?>> request) {
        try {
            RestClient.RequestHeadersSpec<?> spec = request.get();
            BearerTokens.current().ifPresent(t -> spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + t));
            CopyDto dto = spec.retrieve().body(CopyDto.class);
            return dto == null ? Optional.empty()
                    : Optional.of(new CopyInfo(dto.id(), dto.titleId(), dto.titleName(), dto.barcode(), dto.status(), dto.coverPrice()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw new DependencyUnavailableException("catalog-service từ chối tra bản sao (" + e.getStatusCode() + ")");
        } catch (RestClientException e) {
            throw new DependencyUnavailableException("catalog-service", e);
        }
    }

    @Override
    public void changeStatus(UUID copyId, CopyStatus target) {
        try {
            RestClient.RequestBodySpec spec = client.put().uri("/internal/book-copies/{id}/status", copyId);
            BearerTokens.current().ifPresent(t -> spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + t));
            spec.body(Map.of("status", target.name())).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new NotFoundException("COPY_NOT_FOUND", "Không tìm thấy bản sao: " + copyId);
        } catch (HttpClientErrorException.Conflict e) {
            throw new BorrowingRejectedException("COPY_STATUS_CONFLICT",
                    "Catalog không cho chuyển bản sao " + copyId + " sang " + target + " từ trạng thái hiện tại");
        } catch (HttpClientErrorException e) {
            throw new DependencyUnavailableException("catalog-service từ chối đổi trạng thái bản sao (" + e.getStatusCode() + ")");
        } catch (RestClientException e) {
            throw new DependencyUnavailableException("catalog-service", e);
        }
    }
}
