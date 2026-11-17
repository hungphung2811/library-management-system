package lms.system.menu.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.system.menu.application.MenuCachePort;
import lms.system.menu.domain.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cache cây menu chưa lọc của tenant trên Redis (ADR-0007 mục 2.5), khóa {@code tenant:{id}:system:menu:tree}.
 * Mọi lỗi Redis chỉ log WARN (get coi như trượt, put/evict bỏ qua) để không làm hỏng request. {@code evict} trong
 * transaction ghi thì hoãn tới sau commit; rollback thì không xóa.
 */
public class RedisMenuCacheAdapter implements MenuCachePort {

    private static final Logger log = LoggerFactory.getLogger(RedisMenuCacheAdapter.class);
    private static final TypeReference<List<CachedMenu>> TYPE = new TypeReference<>() { };

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisMenuCacheAdapter(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    public static String key(UUID tenantId) {
        return "tenant:" + tenantId + ":system:menu:tree";
    }

    @Override
    public Optional<List<MenuItem>> get(UUID tenantId) {
        try {
            String json = redis.opsForValue().get(key(tenantId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json, TYPE).stream().map(CachedMenu::toDomain).toList());
        } catch (Exception e) {
            log.warn("Đọc cache menu lỗi (tenant {}), rơi về DB: {}", tenantId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(UUID tenantId, List<MenuItem> items) {
        try {
            String json = mapper.writeValueAsString(items.stream().map(CachedMenu::from).toList());
            redis.opsForValue().set(key(tenantId), json, ttl);
        } catch (Exception e) {
            log.warn("Ghi cache menu lỗi (tenant {}): {}", tenantId, e.toString());
        }
    }

    @Override
    public void evict(UUID tenantId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(tenantId);
                }
            });
        } else {
            delete(tenantId);
        }
    }

    private void delete(UUID tenantId) {
        try {
            redis.delete(key(tenantId));
        } catch (Exception e) {
            log.warn("Xóa cache menu lỗi (tenant {}), cache cũ tối đa {}: {}", tenantId, ttl, e.toString());
        }
    }

    /** Dạng lưu trong Redis, tách khỏi domain để domain không phụ thuộc Jackson. */
    record CachedMenu(UUID id, UUID tenantId, UUID parentId, String code, String label, String path, String icon,
                      int sortOrder, boolean enabled, String permissionKey, long version, Instant createdAt,
                      Instant updatedAt, String module) {

        static CachedMenu from(MenuItem i) {
            return new CachedMenu(i.id(), i.tenantId(), i.parentId(), i.code(), i.label(), i.path(), i.icon(),
                    i.sortOrder(), i.enabled(), i.permissionKey(), i.version(), i.createdAt(), i.updatedAt(), i.module());
        }

        MenuItem toDomain() {
            return new MenuItem(id, tenantId, parentId, code, label, path, icon, sortOrder, enabled, permissionKey,
                    version, createdAt, updatedAt, module);
        }
    }
}
