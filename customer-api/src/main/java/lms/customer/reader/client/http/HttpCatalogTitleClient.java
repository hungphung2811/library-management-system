package lms.customer.reader.client.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lms.customer.reader.client.CatalogTitleClient;
import lms.customer.web.PublicApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code POST /internal/books/lookup} của catalog-service (khóa {@code TitleController/lookup}, đã
 * {@code @StorefrontAccessible}) bằng token dịch vụ. Gom id thành lô (tối đa {@link #MAX_BATCH} theo catalog), nên danh
 * sách đặt trước N mục chỉ tốn đúng một lời gọi (không N+1). Mọi lỗi phía catalog thành "không có tên", không làm hỏng
 * danh sách; chỉ log tối đa một dòng mỗi phút, không kèm id.
 */
public class HttpCatalogTitleClient implements CatalogTitleClient {

    /** Trần số id mỗi lời gọi của catalog ({@code LookupTitlesRequest.MAX_IDS}). */
    static final int MAX_BATCH = 100;
    private static final long WARN_INTERVAL_MILLIS = 60_000;
    private static final Logger log = LoggerFactory.getLogger(HttpCatalogTitleClient.class);

    private final AtomicLong lastWarnMillis = new AtomicLong();
    private final RestClient client;

    public HttpCatalogTitleClient(RestClient client) {
        this.client = client;
    }

    @Override
    public Map<UUID, String> titleNames(String serviceToken, Collection<UUID> titleIds) {
        Map<UUID, String> names = new HashMap<>();
        if (titleIds == null || titleIds.isEmpty()) {
            return names;
        }
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(titleIds));
        ids.removeIf(java.util.Objects::isNull);
        for (int from = 0; from < ids.size(); from += MAX_BATCH) {
            List<UUID> batch = ids.subList(from, Math.min(from + MAX_BATCH, ids.size()));
            try {
                List<UpstreamTitle> body = client.post().uri("/internal/books/lookup")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                        .body(new LookupBody(List.copyOf(batch))).retrieve()
                        .body(new ParameterizedTypeReference<List<UpstreamTitle>>() { });
                if (body != null) {
                    for (UpstreamTitle t : body) {
                        if (t != null && t.id() != null && t.name() != null) {
                            names.put(t.id(), t.name());
                        }
                    }
                }
            } catch (RestClientException | PublicApiException ex) {
                warnLimited("Không tra được tên sách từ catalog: " + ex.getClass().getSimpleName());
            }
        }
        return names;
    }

    private void warnLimited(String message) {
        long now = System.currentTimeMillis();
        long last = lastWarnMillis.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarnMillis.compareAndSet(last, now)) {
            log.warn("{} (log tối đa một dòng mỗi phút)", message);
        }
    }

    record LookupBody(List<UUID> ids) {
    }

    /** Chỉ lấy id và tên; các trường khác của catalog (giá bìa, ISBN...) bị bỏ qua. */
    record UpstreamTitle(UUID id, String name) {
    }
}
