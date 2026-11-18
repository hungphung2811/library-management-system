package lms.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.function.Supplier;

/** Cache tắt (LMS_CACHE_ENABLED=false) hoặc không có Redis: luôn chạy thẳng loader. */
public class NoOpRequestCache implements RequestCache {

    @Override
    public <T> T get(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        return loader.get();
    }

    @Override
    public <T> T getGlobal(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        return loader.get();
    }

    @Override
    public void evict(String namespace, Object key) {
    }

    @Override
    public void evictNamespace(String namespace) {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
