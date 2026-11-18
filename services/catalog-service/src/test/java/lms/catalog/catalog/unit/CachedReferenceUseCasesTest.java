package lms.catalog.catalog.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lms.cache.NoOpRequestCache;
import lms.cache.RequestCache;
import lms.catalog.catalog.application.ReferenceOption;
import lms.catalog.catalog.application.ReferencePageResponse;
import lms.catalog.catalog.application.ReferenceResponse;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.infrastructure.cache.CachedReferenceUseCases;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Cache danh mục tham chiếu: lần đọc thứ hai không chạm kho, ghi thì xóa, hai tenant tách biệt, tắt cache thì như cũ. */
class CachedReferenceUseCasesTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    /** Cache trong bộ nhớ theo tenant hiện tại; giá trị đi qua JSON bằng ObjectMapper kiểu Spring Boot như bản Redis. */
    static class InMemoryRequestCache implements RequestCache {
        final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        final Map<String, String> store = new HashMap<>();

        private String scope(String namespace) {
            return TenantContext.require() + "|" + namespace + "|";
        }

        @Override
        public <T> T get(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            String k = scope(namespace) + key;
            try {
                String json = store.get(k);
                if (json != null) {
                    return mapper.readValue(json, mapper.getTypeFactory().constructType(type.getType()));
                }
                T value = loader.get();
                store.put(k, mapper.writeValueAsString(value));
                return value;
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public <T> T getGlobal(String namespace, Object key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
            return loader.get();
        }

        @Override
        public void evict(String namespace, Object key) {
            store.remove(scope(namespace) + key);
        }

        @Override
        public void evictNamespace(String namespace) {
            String prefix = scope(namespace);
            store.keySet().removeIf(k -> k.startsWith(prefix));
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

    /** Kho trong bộ nhớ, đếm số lần đọc. */
    static class CountingRepo implements ReferenceRepository {
        final Map<UUID, Reference> data = new LinkedHashMap<>();
        int findById;
        int search;
        int options;

        @Override
        public Reference save(Reference r) {
            data.put(r.id(), r);
            return r;
        }

        @Override
        public Optional<Reference> findById(ReferenceKind kind, UUID id) {
            findById++;
            return Optional.ofNullable(data.get(id)).filter(r -> r.kind() == kind);
        }

        @Override
        public List<Reference> findByIds(ReferenceKind kind, Collection<UUID> ids) {
            return ids.stream().map(data::get).filter(r -> r != null && r.kind() == kind).toList();
        }

        @Override
        public boolean existsByNameKey(ReferenceKind kind, String nameKey, UUID excludeId) {
            return data.values().stream().anyMatch(r -> r.kind() == kind && r.nameKey().equals(nameKey)
                    && !r.id().equals(excludeId));
        }

        @Override
        public boolean hasChildren(UUID categoryId) {
            return data.values().stream().anyMatch(r -> categoryId.equals(r.parentId()));
        }

        @Override
        public Page search(ReferenceKind kind, String keyword, Boolean active, UUID parentId, int page, int size) {
            search++;
            List<Reference> all = data.values().stream().filter(r -> r.kind() == kind)
                    .filter(r -> keyword == null || r.nameKey().contains(keyword.toLowerCase()))
                    .filter(r -> active == null || r.active() == active)
                    .filter(r -> parentId == null || parentId.equals(r.parentId()))
                    .sorted(Comparator.comparing(Reference::nameKey)).toList();
            int from = Math.min(page * size, all.size());
            return new Page(all.subList(from, Math.min(from + size, all.size())), all.size());
        }

        @Override
        public List<Reference> options(ReferenceKind kind, String keyword, int limit) {
            options++;
            return new ArrayList<>(data.values().stream().filter(r -> r.kind() == kind && r.active())
                    .sorted(Comparator.comparing(Reference::nameKey)).limit(limit).toList());
        }
    }

    private static final ReferenceSearchQuery ALL = new ReferenceSearchQuery(null, null, null, 0, 20);

    private final CountingRepo repo = new CountingRepo();
    private final InMemoryRequestCache cache = new InMemoryRequestCache();
    private final ReferenceUseCases cached = CachedReferenceUseCases.create(repo, cache);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void docLanHaiKhongChamKhoVaGiuNguyenDuLieuQuaJson() {
        TenantContext.set(TENANT_A);
        ReferenceExtras extras = ReferenceExtras.author(1941, null, "Việt Nam", null, "https://example.org");
        ReferenceResponse created = cached.create().execute(ReferenceKind.AUTHOR, "Nguyễn Nhật Ánh", "mô tả", null,
                extras);

        ReferenceResponse first = cached.get().execute(ReferenceKind.AUTHOR, created.id());
        ReferenceResponse second = cached.get().execute(ReferenceKind.AUTHOR, created.id());
        assertEquals(1, repo.findById);
        assertEquals(first, second);
        assertEquals("Việt Nam", second.extras().nationality());
        assertEquals(created.id(), second.id());

        ReferencePageResponse p1 = cached.search().execute(ReferenceKind.AUTHOR, ALL);
        ReferencePageResponse p2 = cached.search().execute(ReferenceKind.AUTHOR, ALL);
        assertEquals(1, repo.search);
        assertEquals(p1, p2);
        assertEquals(1, p2.total());

        List<ReferenceOption> o1 = cached.options().execute(ReferenceKind.AUTHOR, null);
        List<ReferenceOption> o2 = cached.options().execute(ReferenceKind.AUTHOR, null);
        assertEquals(1, repo.options);
        assertEquals(o1, o2);

        // Bộ lọc khác nhau là khóa khác nhau
        cached.search().execute(ReferenceKind.AUTHOR, new ReferenceSearchQuery("nguyễn", null, null, 0, 20));
        assertEquals(2, repo.search);
    }

    @Test
    void ghiXongDocLaiThayDuLieuMoi() {
        TenantContext.set(TENANT_A);
        ReferenceResponse c = cached.create().execute(ReferenceKind.CATEGORY, "Văn học", null, null);
        assertEquals("Văn học", cached.get().execute(ReferenceKind.CATEGORY, c.id()).name());
        assertEquals(1, cached.search().execute(ReferenceKind.CATEGORY, ALL).total());
        assertEquals(1, cached.options().execute(ReferenceKind.CATEGORY, null).size());

        cached.update().execute(ReferenceKind.CATEGORY, c.id(), "Văn học VN", null, null);
        assertEquals("Văn học VN", cached.get().execute(ReferenceKind.CATEGORY, c.id()).name());
        assertEquals("Văn học VN", cached.search().execute(ReferenceKind.CATEGORY, ALL).items().get(0).name());

        cached.create().execute(ReferenceKind.CATEGORY, "Khoa học", null, null);
        assertEquals(2, cached.search().execute(ReferenceKind.CATEGORY, ALL).total());
        assertEquals(2, cached.options().execute(ReferenceKind.CATEGORY, null).size());

        cached.setStatus().execute(ReferenceKind.CATEGORY, c.id(), false);
        assertEquals(false, cached.get().execute(ReferenceKind.CATEGORY, c.id()).active());
        assertEquals(1, cached.options().execute(ReferenceKind.CATEGORY, null).size());
    }

    @Test
    void thayDoiThuMucChaCapNhatTenChaTrongDanhSachCon() {
        TenantContext.set(TENANT_A);
        ReferenceResponse parent = cached.create().execute(ReferenceKind.CATEGORY, "Cha", null, null);
        ReferenceResponse child = cached.create().execute(ReferenceKind.CATEGORY, "Con", null, parent.id());
        assertEquals("Cha", cached.get().execute(ReferenceKind.CATEGORY, child.id()).parentName());

        cached.update().execute(ReferenceKind.CATEGORY, parent.id(), "Cha mới", null, null);
        assertEquals("Cha mới", cached.get().execute(ReferenceKind.CATEGORY, child.id()).parentName());
    }

    @Test
    void ghiLoaiNayKhongXoaCacheLoaiKhac() {
        TenantContext.set(TENANT_A);
        ReferenceResponse pub = cached.create().execute(ReferenceKind.PUBLISHER, "NXB Trẻ", null, null);
        cached.get().execute(ReferenceKind.PUBLISHER, pub.id());
        assertEquals(1, repo.findById);

        cached.create().execute(ReferenceKind.AUTHOR, "Tác giả", null, null);
        cached.get().execute(ReferenceKind.PUBLISHER, pub.id());
        assertEquals(1, repo.findById);
    }

    @Test
    void haiTenantKhongThayCacheCuaNhau() {
        TenantContext.set(TENANT_A);
        ReferenceResponse c = cached.create().execute(ReferenceKind.SHELF_LOCATION, "A-01", null, null);
        cached.get().execute(ReferenceKind.SHELF_LOCATION, c.id());
        cached.get().execute(ReferenceKind.SHELF_LOCATION, c.id());
        assertEquals(1, repo.findById);

        // Tenant B cùng khóa: phải trượt cache, không nhận bản của A
        TenantContext.set(TENANT_B);
        cached.get().execute(ReferenceKind.SHELF_LOCATION, c.id());
        assertEquals(2, repo.findById);

        // B ghi (xóa namespace của B) không làm mất cache của A
        cached.create().execute(ReferenceKind.SHELF_LOCATION, "B-01", null, null);
        TenantContext.set(TENANT_A);
        cached.get().execute(ReferenceKind.SHELF_LOCATION, c.id());
        assertEquals(2, repo.findById);
    }

    @Test
    void loiNghiepVuKhongBiCache() {
        TenantContext.set(TENANT_A);
        UUID missing = UUID.randomUUID();
        assertThrows(ReferenceNotFoundException.class, () -> cached.get().execute(ReferenceKind.AUTHOR, missing));
        assertThrows(ReferenceNotFoundException.class, () -> cached.get().execute(ReferenceKind.AUTHOR, missing));
        assertEquals(2, repo.findById);
    }

    @Test
    void tatCacheThiMoiLanDocDeuChamKhoNhuCu() {
        ReferenceUseCases plain = CachedReferenceUseCases.create(repo, new NoOpRequestCache());
        TenantContext.set(TENANT_A);
        ReferenceResponse c = plain.create().execute(ReferenceKind.AUTHOR, "Tố Hữu", null, null);
        assertNotNull(plain.get().execute(ReferenceKind.AUTHOR, c.id()));
        plain.get().execute(ReferenceKind.AUTHOR, c.id());
        plain.search().execute(ReferenceKind.AUTHOR, ALL);
        plain.search().execute(ReferenceKind.AUTHOR, ALL);
        plain.options().execute(ReferenceKind.AUTHOR, null);
        plain.options().execute(ReferenceKind.AUTHOR, null);
        assertEquals(2, repo.findById);
        assertEquals(2, repo.search);
        assertEquals(2, repo.options);

        plain.update().execute(ReferenceKind.AUTHOR, c.id(), "Tố Hữu 2", null, null);
        assertEquals("Tố Hữu 2", plain.get().execute(ReferenceKind.AUTHOR, c.id()).name());
    }
}
