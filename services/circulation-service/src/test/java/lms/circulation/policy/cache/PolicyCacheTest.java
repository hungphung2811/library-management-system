package lms.circulation.policy.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lms.cache.NoOpRequestCache;
import lms.cache.RequestCache;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.UpdateCirculationPolicyRequest;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.domain.CirculationPolicySnapshot;
import lms.circulation.policy.infrastructure.cache.CachedPolicyReader;
import lms.circulation.policy.infrastructure.cache.EvictingUpdatePolicyUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class PolicyCacheTest {

    /** Cache giả theo tenant hiện tại, serialize qua JSON như Redis thật. */
    static class FakeCache implements RequestCache {
        final Map<String, String> store = new HashMap<>();
        final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        String tenant = "t1";

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String ns, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            String k = tenant + "|" + ns + "|" + key;
            try {
                String hit = store.get(k);
                if (hit != null) {
                    return (T) mapper.readValue(hit, mapper.constructType(type.getType()));
                }
                T value = loader.get();
                store.put(k, mapper.writeValueAsString(value));
                return value;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public <T> T getGlobal(String ns, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            return loader.get();
        }

        @Override
        public void evict(String ns, Object key) {
            store.remove(tenant + "|" + ns + "|" + key);
        }

        @Override
        public void evictNamespace(String ns) {
            store.keySet().removeIf(k -> k.startsWith(tenant + "|" + ns + "|"));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    /** Kho theo tenant, đếm số lần đọc. */
    static class CountingRepo implements CirculationPolicyRepository {
        final Map<String, CirculationPolicySnapshot> byTenant = new HashMap<>();
        final FakeCache cache;
        int finds;

        CountingRepo(FakeCache cache) {
            this.cache = cache;
        }

        @Override
        public Optional<CirculationPolicySnapshot> find() {
            finds++;
            return Optional.ofNullable(byTenant.get(cache.tenant));
        }

        @Override
        public CirculationPolicySnapshot save(CirculationPolicy policy, UUID by, Instant now) {
            CirculationPolicySnapshot old = byTenant.get(cache.tenant);
            CirculationPolicySnapshot s = new CirculationPolicySnapshot(policy, old == null ? 1 : old.version() + 1, now, by);
            byTenant.put(cache.tenant, s);
            return s;
        }
    }

    final FakeCache cache = new FakeCache();
    final CountingRepo repo = new CountingRepo(cache);
    final Clock clock = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
    final GetCirculationPolicyUseCase rawGet = new GetCirculationPolicyUseCase(repo);
    final CachedPolicyReader cachedGet = new CachedPolicyReader(rawGet, cache);
    final EvictingUpdatePolicyUseCase update = new EvictingUpdatePolicyUseCase(repo, clock, cache);

    static UpdateCirculationPolicyRequest request(Long version, int loanDays) {
        return new UpdateCirculationPolicyRequest(loanDays, 1, 7, 8, 2000L, 1, 5, version);
    }

    @Test
    void getLanHaiKhongChamDb() {
        cachedGet.get();
        cachedGet.get();
        assertEquals(1, repo.finds);
    }

    @Test
    void ghiXongGetThayDuLieuMoi() {
        assertEquals(14, cachedGet.get().loanDays());
        CirculationPolicyResponse saved = update.execute(request(null, 30), UUID.randomUUID());
        CirculationPolicyResponse after = cachedGet.get();
        assertEquals(30, after.loanDays());
        assertEquals(saved.version(), after.version());
        assertEquals(saved.updatedAt(), after.updatedAt());
        assertNotNull(after.updatedAt());
    }

    @Test
    void haiTenantKhongThayCacheCuaNhau() {
        cache.tenant = "t1";
        update.execute(request(null, 30), UUID.randomUUID());
        assertEquals(30, cachedGet.get().loanDays());
        cache.tenant = "t2";
        assertEquals(14, cachedGet.get().loanDays());
        update.execute(request(null, 21), UUID.randomUUID());
        assertEquals(21, cachedGet.get().loanDays());
        cache.tenant = "t1";
        assertEquals(30, cachedGet.get().loanDays());
    }

    @Test
    void nghiepVuDocThangKhongQuaCache() {
        // use case gốc (bean mà mượn/gia hạn/phạt/đặt trước inject) luôn đọc repo, thấy ngay giá trị mới
        cachedGet.get();
        int before = repo.finds;
        repo.save(new CirculationPolicy(40, 1, 7, 8, 2000L, 1, 5), UUID.randomUUID(), Instant.now(clock));
        assertEquals(40, rawGet.execute().loanDays());
        assertEquals(before + 1, repo.finds);
    }

    @Test
    void cacheTatHanhViNhuCu() {
        CachedPolicyReader off = new CachedPolicyReader(rawGet, new NoOpRequestCache());
        off.get();
        off.get();
        assertEquals(2, repo.finds);
        assertEquals(14, off.get().loanDays());
    }
}
