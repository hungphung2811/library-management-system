package lms.circulation.reporting.infrastructure.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.reporting.application.CopyLabelLookupPort;
import lms.security.permission.ActAsTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi {@code POST /internal/book-copies/lookup} của catalog-service (khóa {@code BookCopyController/lookup}) bằng token
 * của người xuất báo cáo, chuyển tiếp {@code X-Tenant-Id} khi platform admin hành động thay tenant. Lỗi chỉ ghi cảnh báo
 * và bỏ qua lô đó (báo cáo vẫn xuất, ô tên đầu sách và barcode để trống).
 */
public class CatalogCopyLabelRestClientAdapter implements CopyLabelLookupPort {

    private static final Logger log = LoggerFactory.getLogger(CatalogCopyLabelRestClientAdapter.class);

    private final RestClient client;

    public CatalogCopyLabelRestClientAdapter(RestClient client) {
        this.client = client;
    }

    record LookupRequest(List<UUID> ids) {
    }

    /** Chỉ lấy các trường cần dùng; trường khác của catalog (titleId, isbn, status) được bỏ qua. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record CopyDto(UUID id, String titleName, String barcode) {
    }

    @Override
    public Map<UUID, CopyLabel> labels(Collection<UUID> bookCopyIds) {
        Map<UUID, CopyLabel> result = new HashMap<>();
        List<UUID> ids = List.copyOf(bookCopyIds);
        for (int from = 0; from < ids.size(); from += MAX_BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(from + MAX_BATCH, ids.size()));
            try {
                RestClient.RequestBodySpec spec = client.post().uri("/internal/book-copies/lookup");
                BearerTokens.current().ifPresent(t -> spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + t));
                ActAsTenant.current().ifPresent(tid -> spec.header(ActAsTenant.HEADER, tid));
                CopyDto[] body = spec.body(new LookupRequest(batch)).retrieve().body(CopyDto[].class);
                if (body != null) {
                    for (CopyDto dto : body) {
                        if (dto != null && dto.id() != null) result.put(dto.id(), new CopyLabel(dto.titleName(), dto.barcode()));
                    }
                }
            } catch (RestClientException e) {
                log.warn("Không tra được tên đầu sách theo lô ({} id) từ catalog-service: {}", batch.size(), e.getMessage());
            }
        }
        return result;
    }
}
