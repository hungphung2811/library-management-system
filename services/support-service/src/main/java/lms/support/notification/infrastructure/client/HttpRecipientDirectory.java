package lms.support.notification.infrastructure.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.support.notification.application.RecipientDirectoryPort;
import org.springframework.web.client.RestClient;

/**
 * Tra email và tên hiển thị người nhận ở identity qua POST /internal/notification-recipients/lookup (ADR-0030 D5), tối
 * đa 100 id mỗi lần. Xác thực bằng bí mật riêng trong header X-Notification-Client-Secret (không dùng INTERNAL_API_KEY).
 * Mọi lỗi (mạng, 4xx, 5xx) thành RecipientDirectoryException để dispatcher thử lại sau; không log thân phản hồi.
 */
public class HttpRecipientDirectory implements RecipientDirectoryPort {

    public static final String SECRET_HEADER = "X-Notification-Client-Secret";
    static final int MAX_IDS = 100;

    record LookupRequest(UUID tenantId, List<UUID> userIds) {
    }

    record RecipientBody(UUID userId, String email, String displayName, boolean active) {
    }

    record LookupResponse(String tenantDisplayName, List<RecipientBody> recipients) {
    }

    private final RestClient client;
    private final String secret;

    public HttpRecipientDirectory(RestClient client, String secret) {
        this.client = client;
        this.secret = secret == null ? "" : secret;
    }

    @Override
    public Lookup lookup(UUID tenantId, Collection<UUID> userIds) {
        if (secret.isBlank()) {
            throw new RecipientDirectoryException("Chưa cấu hình NOTIFICATION_CLIENT_SECRET", null);
        }
        List<UUID> all = new ArrayList<>(userIds);
        Map<UUID, Recipient> found = new HashMap<>();
        String tenantName = null;
        int calls = 0;
        for (int i = 0; i < all.size() || calls == 0; i += MAX_IDS) {
            List<UUID> chunk = all.subList(Math.min(i, all.size()), Math.min(i + MAX_IDS, all.size()));
            LookupResponse r = call(tenantId, chunk);
            calls++;
            if (r == null) continue;
            if (tenantName == null) tenantName = r.tenantDisplayName();
            if (r.recipients() != null) {
                for (RecipientBody b : r.recipients()) {
                    found.put(b.userId(), new Recipient(b.userId(), b.email(), b.displayName(), b.active()));
                }
            }
        }
        return new Lookup(tenantName == null ? "" : tenantName, found);
    }

    private LookupResponse call(UUID tenantId, List<UUID> ids) {
        try {
            return client.post().uri("/internal/notification-recipients/lookup")
                    .header(SECRET_HEADER, secret)
                    .body(new LookupRequest(tenantId, ids))
                    .retrieve().body(LookupResponse.class);
        } catch (RuntimeException e) {
            // Không đưa message gốc ra (có thể chứa URL/chi tiết); chỉ lớp lỗi
            throw new RecipientDirectoryException("Tra người nhận ở identity lỗi: " + e.getClass().getSimpleName(), e);
        }
    }
}
