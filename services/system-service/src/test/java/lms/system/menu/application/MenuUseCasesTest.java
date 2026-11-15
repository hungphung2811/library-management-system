package lms.system.menu.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.system.menu.domain.MenuDetails;
import lms.system.menu.domain.MenuErrorCode;
import lms.system.menu.domain.MenuException;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuNode;
import lms.system.menu.domain.MenuViewer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MenuUseCasesTest {

    private static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private FakeRepository repository;
    private FakeCache cache;
    private GetMyMenuUseCase getMy;
    private ListMenusUseCase list;
    private CreateMenuUseCase create;
    private UpdateMenuUseCase update;
    private DeleteMenuUseCase delete;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        cache = new FakeCache();
        getMy = new GetMyMenuUseCase(repository, cache);
        list = new ListMenusUseCase(repository);
        create = new CreateMenuUseCase(repository, cache, CLOCK);
        update = new UpdateMenuUseCase(repository, cache, CLOCK);
        delete = new DeleteMenuUseCase(repository, cache);
    }

    private MenuItem createLeaf(UUID tenant, String code, UUID parent, String key) {
        return create.execute(tenant, new CreateMenuUseCase.Command(code,
                new MenuDetails("L " + code, "/" + code, null, parent, 0, true, key)));
    }

    private static List<String> codes(List<MenuNode> nodes) {
        return nodes.stream().map(n -> n.item().code()).toList();
    }

    // ---------- đọc ----------

    @Test
    void tokenKhongCoTenantTraRong() {
        assertThat(getMy.execute(null, new MenuViewer(true, Set.of()))).isEmpty();
        assertThat(repository.findAllCalls).isZero();
    }

    @Test
    void truotCacheDocDbRoiGhiCache() {
        createLeaf(T1, "home", null, null);
        cache.store.clear();
        repository.findAllCalls = 0;

        assertThat(codes(getMy.execute(T1, new MenuViewer(false, Set.of())))).containsExactly("home");
        assertThat(repository.findAllCalls).isEqualTo(1);
        assertThat(cache.store).containsKey(T1);
    }

    @Test
    void trungCacheKhongDocDb() {
        createLeaf(T1, "home", null, null);
        getMy.execute(T1, new MenuViewer(false, Set.of()));
        repository.findAllCalls = 0;

        assertThat(codes(getMy.execute(T1, new MenuViewer(false, Set.of())))).containsExactly("home");
        assertThat(repository.findAllCalls).isZero();
    }

    @Test
    void cacheLuuCayChuaLocCacNguoiDungKhacQuyenDungChung() {
        createLeaf(T1, "home", null, null);
        createLeaf(T1, "admin", null, "AccessControlController/listRoles");

        List<MenuNode> normal = getMy.execute(T1, new MenuViewer(false, Set.of()));
        List<MenuNode> admin = getMy.execute(T1, new MenuViewer(true, Set.of()));
        List<MenuNode> granted = getMy.execute(T1, new MenuViewer(false, Set.of("AccessControlController/listRoles")));

        assertThat(codes(normal)).containsExactly("home");
        assertThat(codes(admin)).containsExactlyInAnyOrder("home", "admin");
        assertThat(codes(granted)).containsExactlyInAnyOrder("home", "admin");
        assertThat(cache.store.get(T1)).hasSize(2);
    }

    @Test
    void loiCacheKhongLamHongRequest() {
        createLeaf(T1, "home", null, null);
        cache.failing = true;
        assertThat(codes(getMy.execute(T1, new MenuViewer(false, Set.of())))).containsExactly("home");
    }

    @Test
    void cacheTheoTenantKhongLanNhau() {
        createLeaf(T1, "t1only", null, null);
        createLeaf(T2, "t2only", null, null);
        assertThat(codes(getMy.execute(T1, new MenuViewer(false, Set.of())))).containsExactly("t1only");
        assertThat(codes(getMy.execute(T2, new MenuViewer(false, Set.of())))).containsExactly("t2only");
    }

    @Test
    void danhSachQuanTriGomCaMucTatVaDocThangDb() {
        createLeaf(T1, "a", null, null);
        MenuItem b = createLeaf(T1, "b", null, null);
        update.execute(T1, new UpdateMenuUseCase.Command(b.id(),
                new MenuDetails("B", "/b", null, null, 1, false, null), b.version()));
        repository.findAllCalls = 0;

        List<MenuItem> all = list.execute(T1);
        assertThat(all).extracting(MenuItem::code).containsExactly("a", "b");
        assertThat(all.get(1).enabled()).isFalse();
        assertThat(repository.findAllCalls).isEqualTo(1);
    }

    // ---------- ghi và phá cache ----------

    @Test
    void taoLuuVaPhaCache() {
        getMy.execute(T1, new MenuViewer(false, Set.of()));
        assertThat(cache.store).containsKey(T1);

        MenuItem saved = createLeaf(T1, "home", null, null);

        assertThat(saved.tenantId()).isEqualTo(T1);
        assertThat(repository.items.values()).extracting(MenuItem::code).containsExactly("home");
        assertThat(cache.evicted).containsExactly(T1);
        assertThat(cache.store).doesNotContainKey(T1);
    }

    @Test
    void taoLoiNghiepVuKhongLuuKhongPhaCache() {
        createLeaf(T1, "home", null, null);
        cache.evicted.clear();

        assertThatThrownBy(() -> createLeaf(T1, "home", null, null))
                .isInstanceOfSatisfying(MenuException.class,
                        e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_CODE_DUPLICATED));
        assertThat(repository.items).hasSize(1);
        assertThat(cache.evicted).isEmpty();
    }

    @Test
    void codeTrungGiuaHaiTenantLaHopLe() {
        createLeaf(T1, "home", null, null);
        createLeaf(T2, "home", null, null);
        assertThat(repository.items).hasSize(2);
    }

    @Test
    void suaLuuVaPhaCacheVaXungDotVersionBiTuChoi() {
        MenuItem a = createLeaf(T1, "a", null, null);
        cache.evicted.clear();

        MenuItem saved = update.execute(T1, new UpdateMenuUseCase.Command(a.id(),
                new MenuDetails("Mới", "/moi", null, null, 3, true, null), a.version()));
        assertThat(saved.label()).isEqualTo("Mới");
        assertThat(saved.version()).isEqualTo(a.version() + 1);
        assertThat(cache.evicted).containsExactly(T1);

        // dùng lại version cũ => xung đột, không phá cache thêm
        assertThatThrownBy(() -> update.execute(T1, new UpdateMenuUseCase.Command(a.id(),
                new MenuDetails("Cũ", "/cu", null, null, 3, true, null), a.version())))
                .isInstanceOfSatisfying(MenuException.class,
                        e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_VERSION_CONFLICT));
        assertThat(cache.evicted).hasSize(1);
        assertThat(repository.items.get(a.id()).label()).isEqualTo("Mới");
    }

    @Test
    void suaMucCuaTenantKhacLaKhongTimThay() {
        MenuItem a = createLeaf(T1, "a", null, null);
        assertThatThrownBy(() -> update.execute(T2, new UpdateMenuUseCase.Command(a.id(),
                new MenuDetails("X", "/x", null, null, 0, true, null), 0)))
                .isInstanceOfSatisfying(MenuException.class,
                        e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_NOT_FOUND));
    }

    @Test
    void suaChuTrinhBiTuChoiVaKhongLuu() {
        MenuItem a = create.execute(T1, new CreateMenuUseCase.Command("a",
                new MenuDetails("A", null, null, null, 0, true, null)));
        MenuItem b = create.execute(T1, new CreateMenuUseCase.Command("b",
                new MenuDetails("B", "/b", null, a.id(), 0, true, null)));
        repository.saveCalls = 0;

        assertThatThrownBy(() -> update.execute(T1, new UpdateMenuUseCase.Command(a.id(),
                new MenuDetails("A", null, null, b.id(), 0, true, null), a.version())))
                .isInstanceOfSatisfying(MenuException.class,
                        e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_CYCLE));
        assertThat(repository.saveCalls).isZero();
    }

    @Test
    void xoaLaVaPhaCache() {
        MenuItem a = createLeaf(T1, "a", null, null);
        cache.evicted.clear();

        delete.execute(T1, a.id());

        assertThat(repository.items).isEmpty();
        assertThat(cache.evicted).containsExactly(T1);
    }

    @Test
    void xoaNutConConBiTuChoiKhongXoaKhongPhaCache() {
        MenuItem g = create.execute(T1, new CreateMenuUseCase.Command("g",
                new MenuDetails("G", null, null, null, 0, true, null)));
        createLeaf(T1, "child", g.id(), null);
        cache.evicted.clear();

        assertThatThrownBy(() -> delete.execute(T1, g.id()))
                .isInstanceOfSatisfying(MenuException.class,
                        e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_HAS_CHILDREN));
        assertThat(repository.items).hasSize(2);
        assertThat(repository.deleteCalls).isZero();
        assertThat(cache.evicted).isEmpty();
    }

    @Test
    void xoaMucKhongTonTai() {
        assertThatThrownBy(() -> delete.execute(T1, UUID.randomUUID()))
                .isInstanceOfSatisfying(MenuException.class,
                        e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_NOT_FOUND));
    }

    // ---------- fake ----------

    private static final class FakeRepository implements MenuRepositoryPort {
        final Map<UUID, MenuItem> items = new HashMap<>();
        int findAllCalls;
        int saveCalls;
        int deleteCalls;

        @Override
        public List<MenuItem> findAll(UUID tenantId) {
            findAllCalls++;
            List<MenuItem> out = new ArrayList<>();
            for (MenuItem i : items.values()) {
                if (i.tenantId().equals(tenantId)) {
                    out.add(i);
                }
            }
            return out;
        }

        @Override
        public MenuItem save(MenuItem item) {
            saveCalls++;
            MenuItem existing = items.get(item.id());
            long version = existing == null ? 0 : existing.version() + 1;
            MenuItem stored = new MenuItem(item.id(), item.tenantId(), item.parentId(), item.code(), item.label(),
                    item.path(), item.icon(), item.sortOrder(), item.enabled(), item.permissionKey(), version,
                    item.createdAt(), item.updatedAt());
            items.put(stored.id(), stored);
            return stored;
        }

        @Override
        public void deleteById(UUID tenantId, UUID id) {
            deleteCalls++;
            items.remove(id);
        }
    }

    private static final class FakeCache implements MenuCachePort {
        final Map<UUID, List<MenuItem>> store = new HashMap<>();
        final List<UUID> evicted = new ArrayList<>();
        boolean failing;

        @Override
        public Optional<List<MenuItem>> get(UUID tenantId) {
            if (failing) {
                throw new IllegalStateException("redis down");
            }
            return Optional.ofNullable(store.get(tenantId));
        }

        @Override
        public void put(UUID tenantId, List<MenuItem> items) {
            if (failing) {
                throw new IllegalStateException("redis down");
            }
            store.put(tenantId, List.copyOf(items));
        }

        @Override
        public void evict(UUID tenantId) {
            evicted.add(tenantId);
            store.remove(tenantId);
        }
    }
}
