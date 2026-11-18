package lms.identity.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lms.cache.RequestCache;
import lms.tenant.TenantContext;

/**
 * RequestCache giả trong bộ nhớ cho test: khóa theo tenant hiện tại như bản Redis, giá trị đi qua JSON (kiểm
 * serialize/deserialize của DTO), không có tenant hoặc phạm vi mọi tenant thì chạy thẳng loader.
 */
public class InMemoryRequestCache implements RequestCache {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, String> store = new ConcurrentHashMap<>();
    public int hits;
    public int misses;

    @Override
    public <T> T get(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        if (TenantContext.isAllTenants() || TenantContext.current().isEmpty()) {
            return loader.get();
        }
        return fetch(prefix(namespace) + key, type, loader);
    }

    @Override
    public <T> T getGlobal(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        return fetch("g:" + namespace + ":" + key, type, loader);
    }

    private <T> T fetch(String redisKey, TypeReference<T> type, Supplier<T> loader) {
        try {
            String json = store.get(redisKey);
            if (json != null) {
                hits++;
                return mapper.readValue(json, mapper.getTypeFactory().constructType(type.getType()));
            }
            misses++;
            T value = loader.get();
            if (value != null) {
                store.put(redisKey, mapper.writeValueAsString(value));
            }
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void evict(String namespace, Object key) {
        TenantContext.current().ifPresent(t -> store.remove(prefix(namespace) + key));
    }

    @Override
    public void evictNamespace(String namespace) {
        TenantContext.current().ifPresent(t -> store.keySet().removeIf(k -> k.startsWith(prefix(namespace))));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private static String prefix(String namespace) {
        return "t:" + TenantContext.current().orElseThrow() + ":" + namespace + ":";
    }
}
