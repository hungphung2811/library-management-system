package lms.circulation.reporting.infrastructure.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.reporting.application.ReaderNameLookupPort;
import lms.security.permission.ActAsTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gọi {@code POST /internal/users/lookup} của identity-service (khóa {@code UserController/lookup}) bằng token của người
 * xuất báo cáo, chuyển tiếp {@code X-Tenant-Id} khi platform admin hành động thay tenant. Lỗi chỉ ghi cảnh báo và bỏ qua
 * lô đó (báo cáo vẫn xuất, ô tên để trống). Không log tên người dùng.
 */
public class IdentityUserNameRestClientAdapter implements ReaderNameLookupPort {

    private static final Logger log = LoggerFactory.getLogger(IdentityUserNameRestClientAdapter.class);

    private final RestClient client;

    public IdentityUserNameRestClientAdapter(RestClient client) {
        this.client = client;
    }

    record LookupRequest(List<UUID> ids) {
    }

    record UserNameDto(UUID id, String fullName) {
    }

    @Override
    public Map<UUID, String> names(Collection<UUID> userIds) {
        Map<UUID, String> result = new HashMap<>();
        List<UUID> ids = List.copyOf(userIds);
        for (int from = 0; from < ids.size(); from += MAX_BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(from + MAX_BATCH, ids.size()));
            try {
                RestClient.RequestBodySpec spec = client.post().uri("/internal/users/lookup");
                BearerTokens.current().ifPresent(t -> spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + t));
                ActAsTenant.current().ifPresent(tid -> spec.header(ActAsTenant.HEADER, tid));
                UserNameDto[] body = spec.body(new LookupRequest(batch)).retrieve().body(UserNameDto[].class);
                if (body != null) {
                    for (UserNameDto dto : body) {
                        if (dto != null && dto.id() != null) result.put(dto.id(), dto.fullName());
                    }
                }
            } catch (RestClientException e) {
                log.warn("Không tra được tên độc giả theo lô ({} id) từ identity-service: {}", batch.size(), e.getMessage());
            }
        }
        return result;
    }
}
