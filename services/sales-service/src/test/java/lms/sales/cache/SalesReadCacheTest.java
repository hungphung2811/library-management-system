package lms.sales.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lms.cache.NoOpRequestCache;
import lms.cache.RequestCache;
import lms.sales.settings.application.UpdateSalesSettingsCommand;
import lms.sales.settings.infrastructure.web.SalesSettingsController;
import lms.sales.settings.infrastructure.web.SalesSettingsController.SettingsResponse;
import lms.sales.settings.infrastructure.web.SalesSettingsController.UpdateSettingsRequest;
import lms.sales.stock.infrastructure.web.SupplierController;
import lms.sales.stock.infrastructure.web.SupplierController.SupplierRequest;
import lms.sales.stock.infrastructure.web.SupplierController.SupplierSearchQuery;
import lms.sales.support.Harness;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Cache GET cài đặt và nhà cung cấp: lần hai không chạy lại use case, ghi thì evict, hai tenant tách biệt, cache tắt
 * thì hành vi như cũ. Cache giả đi qua JSON (ObjectMapper) để kiểm tra việc serialize/deserialize view.
 */
class SalesReadCacheTest {

    /** Cache giả theo tenant hiện tại, lưu JSON như Redis thật, đếm số lần loader chạy. */
    static final class FakeCache implements RequestCache {
        final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        final Map<String, String> store = new HashMap<>();
        int loads;

        private String prefix(String ns) {
            return TenantContext.require() + "|" + ns + "|";
        }

        @Override
        public <T> T get(String ns, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            String k = prefix(ns) + key;
            try {
                String json = store.get(k);
                if (json != null) {
                    return mapper.readValue(json, mapper.getTypeFactory().constructType(type.getType()));
                }
                loads++;
                T value = loader.get();
                store.put(k, mapper.writeValueAsString(value));
                return value;
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public <T> T getGlobal(String ns, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            return loader.get();
        }

        @Override
        public void evict(String ns, Object key) {
            store.remove(prefix(ns) + key);
        }

        @Override
        public void evictNamespace(String ns) {
            String p = prefix(ns);
            store.keySet().removeIf(k -> k.startsWith(p));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final Harness h = new Harness();
    private final FakeCache cache = new FakeCache();
    private final SalesSettingsController settings =
            new SalesSettingsController(h.getSettings, h.updateSettings, cache);
    private final SupplierController suppliers = new SupplierController(h.supplierUseCases, cache);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static UpdateSettingsRequest update(int vat, long version) {
        return new UpdateSettingsRequest(vat, true, 7, 0L, 5, 12, version);
    }

    private static SupplierRequest supplier(String name) {
        return new SupplierRequest(name, null, null, null, null, null);
    }

    @Test
    void settingsSecondGetIsServedFromCache() {
        TenantContext.set(tenantA);
        SettingsResponse first = settings.get();
        SettingsResponse second = settings.get();
        assertEquals(1, cache.loads);
        assertEquals(first, second);
        assertEquals(500, second.vatRateBps());
    }

    @Test
    void settingsUpdateEvictsSoNextGetSeesNewData() {
        TenantContext.set(tenantA);
        assertEquals(500, settings.get().vatRateBps());
        settings.update(update(800, 0), null);
        SettingsResponse after = settings.get();
        assertEquals(800, after.vatRateBps());
        assertEquals(1, after.version());
        assertEquals(2, cache.loads);
        assertNotNull(after.updatedAt());
    }

    @Test
    void settingsAreIsolatedPerTenant() {
        TenantContext.set(tenantA);
        settings.update(update(800, 0), null);
        assertEquals(800, settings.get().vatRateBps());
        // bộ giả của Harness không tách tenant; điều cần chứng minh là tenant B KHÔNG đọc mục cache của tenant A
        TenantContext.set(tenantB);
        settings.get();
        assertEquals(2, cache.loads);
        assertEquals(2, cache.store.size());
        TenantContext.set(tenantA);
        assertEquals(800, settings.get().vatRateBps());
        assertEquals(2, cache.loads);
    }

    @Test
    void businessUseCaseReadsSettingsDirectlyNotThroughCache() {
        TenantContext.set(tenantA);
        settings.get();
        h.updateSettings.execute(new UpdateSalesSettingsCommand(tenantA, null, 900, true, 7, 0L, 5, 12, 0L));
        // use case nghiệp vụ thấy ngay giá trị mới dù cache của controller chưa bị evict
        assertEquals(900, h.getSettings.execute(tenantA).vatRateBps());
    }

    @Test
    void suppliersSearchCachedPerQueryAndEvictedOnWrite() {
        TenantContext.set(tenantA);
        var q = new SupplierSearchQuery(null, null, null);
        assertEquals(0, suppliers.search(q).total());
        assertEquals(0, suppliers.search(q).total());
        assertEquals(1, cache.loads);

        var created = suppliers.create(supplier("NCC Một"), null);
        assertEquals(201, created.getStatusCode().value());
        var page = suppliers.search(q);
        assertEquals(1, page.total());
        assertEquals("NCC Một", page.items().get(0).name());
        assertNotNull(page.items().get(0).id());
        assertEquals(2, cache.loads);

        // từ khóa khác là khóa khác
        suppliers.search(new SupplierSearchQuery("xyz", null, null));
        assertEquals(3, cache.loads);

        UUID id = page.items().get(0).id();
        suppliers.update(id, supplier("NCC Hai"), null);
        assertEquals("NCC Hai", suppliers.search(q).items().get(0).name());
        assertEquals(4, cache.loads);
    }

    @Test
    void suppliersAreIsolatedPerTenant() {
        TenantContext.set(tenantA);
        var q = new SupplierSearchQuery(null, null, null);
        suppliers.create(supplier("NCC A"), null);
        assertEquals(1, suppliers.search(q).total());
        // bộ giả của Harness không tách tenant; tenant B phải chạy loader riêng, không dùng mục cache của A
        TenantContext.set(tenantB);
        suppliers.search(q);
        assertEquals(2, cache.loads);
        assertEquals(2, cache.store.size());
    }

    @Test
    void disabledCacheBehavesAsBefore() {
        var plain = new SupplierController(h.supplierUseCases, new NoOpRequestCache());
        var plainSettings = new SalesSettingsController(h.getSettings, h.updateSettings, new NoOpRequestCache());
        TenantContext.set(tenantA);
        var q = new SupplierSearchQuery(null, null, null);
        assertEquals(0, plain.search(q).total());
        plain.create(supplier("NCC X"), null);
        assertEquals(1, plain.search(q).total());
        plainSettings.update(update(700, 0), null);
        assertEquals(700, plainSettings.get().vatRateBps());
        assertTrue(cache.store.isEmpty());
    }
}
