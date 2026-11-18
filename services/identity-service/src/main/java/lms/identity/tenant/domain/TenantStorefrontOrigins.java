package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tập origin storefront của MỘT tenant (ADR-0013): giới hạn số lượng (đếm cả dòng tắt), không trùng trong tenant,
 * bật/tắt, xóa. Chỉ biết dữ liệu của tenant này nên id của tenant khác luôn là "không tìm thấy". Kiểm trùng xuyên
 * tenant do use case làm (đọc kho toàn cục).
 */
public class TenantStorefrontOrigins {

    private final UUID tenantId;
    private final int max;
    private final List<StorefrontOriginEntry> entries;

    public TenantStorefrontOrigins(UUID tenantId, List<StorefrontOriginEntry> existing, int max) {
        this.tenantId = tenantId;
        this.max = max;
        this.entries = new ArrayList<>(existing);
    }

    /**
     * @throws StorefrontOriginDuplicatedException nếu tenant đã có origin này
     * @throws StorefrontOriginLimitExceededException nếu đã đủ số tối đa
     */
    public StorefrontOriginEntry add(StorefrontOrigin origin, UUID actor, Instant at) {
        return add(origin, null, actor, at);
    }

    /** Như {@link #add(StorefrontOrigin, UUID, Instant)} nhưng gắn origin với một Application của tenant (có thể null). */
    public StorefrontOriginEntry add(StorefrontOrigin origin, UUID applicationId, UUID actor, Instant at) {
        if (entries.stream().anyMatch(e -> e.origin().equals(origin.value()))) {
            throw new StorefrontOriginDuplicatedException("Origin đã có trong tenant này: " + origin.value(), tenantId);
        }
        if (entries.size() >= max) throw new StorefrontOriginLimitExceededException(max);
        StorefrontOriginEntry created = new StorefrontOriginEntry(UUID.randomUUID(), tenantId, origin.value(), true, at,
                actor, at, actor, applicationId);
        entries.add(created);
        return created;
    }

    /**
     * @return dòng sau khi đổi; rỗng nếu giá trị không đổi (no-op)
     * @throws StorefrontOriginNotFoundException nếu id không thuộc tenant
     */
    public Optional<StorefrontOriginEntry> setEnabled(UUID originId, boolean enabled, UUID actor, Instant at) {
        StorefrontOriginEntry current = get(originId);
        if (current.enabled() == enabled) return Optional.empty();
        StorefrontOriginEntry changed = current.withEnabled(enabled, at, actor);
        entries.set(entries.indexOf(current), changed);
        return Optional.of(changed);
    }

    /** @throws StorefrontOriginNotFoundException nếu id không thuộc tenant */
    public StorefrontOriginEntry remove(UUID originId) {
        StorefrontOriginEntry current = get(originId);
        entries.remove(current);
        return current;
    }

    public StorefrontOriginEntry get(UUID originId) {
        return entries.stream().filter(e -> e.id().equals(originId)).findFirst().orElseThrow(
                () -> new StorefrontOriginNotFoundException("Không tìm thấy origin " + originId));
    }

    public int max() {
        return max;
    }

    public List<StorefrontOriginEntry> entries() {
        return List.copyOf(entries);
    }
}
