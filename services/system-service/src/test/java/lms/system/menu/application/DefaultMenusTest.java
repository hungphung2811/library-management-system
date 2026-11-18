package lms.system.menu.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lms.system.menu.domain.MenuDetails;
import lms.system.menu.domain.MenuErrorCode;
import lms.system.menu.domain.MenuException;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.domain.MenuNode;
import lms.system.menu.domain.MenuViewer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Menu mặc định (ADR-0009 M3): bộ hằng, GET /me khi tenant chưa có dữ liệu, và InitializeDefaultMenusUseCase. */
class DefaultMenusTest {

    private static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_.:-]+/[A-Za-z0-9_.:-]+$");

    private final Map<UUID, MenuItem> db = new HashMap<>();
    private final Set<UUID> evicted = new HashSet<>();
    private GetMyMenuUseCase getMy;
    private ListMenusUseCase list;
    private InitializeDefaultMenusUseCase init;
    private CreateMenuUseCase create;

    @BeforeEach
    void setUp() {
        MenuRepositoryPort repo = new MenuRepositoryPort() {
            @Override public List<MenuItem> findAll(UUID tenantId) {
                return db.values().stream().filter(i -> i.tenantId().equals(tenantId)).toList();
            }
            @Override public MenuItem save(MenuItem item) { db.put(item.id(), item); return item; }
            @Override public void deleteById(UUID tenantId, UUID id) { db.remove(id); }
        };
        MenuCachePort cache = new MenuCachePort() {
            @Override public Optional<List<MenuItem>> get(UUID tenantId) { return Optional.empty(); }
            @Override public void put(UUID tenantId, List<MenuItem> items) { }
            @Override public void evict(UUID tenantId) { evicted.add(tenantId); }
        };
        getMy = new GetMyMenuUseCase(repo, cache);
        list = new ListMenusUseCase(repo);
        init = new InitializeDefaultMenusUseCase(repo, cache, CLOCK);
        create = new CreateMenuUseCase(repo, cache, CLOCK);
    }

    private static List<String> codes(List<MenuNode> nodes) {
        return nodes.stream().map(n -> n.item().code()).toList();
    }

    private static MenuViewer viewer(boolean admin, Set<String> perms, String... modules) {
        return new MenuViewer(admin, perms, Set.of(modules));
    }

    // ---------- bộ hằng ----------

    @Test
    void boMacDinhHopLeVaChaDungTruocCon() {
        Set<String> seen = new HashSet<>();
        for (DefaultMenus.Entry e : DefaultMenus.ENTRIES) {
            assertThat(e.parentCode() == null || seen.contains(e.parentCode())).as(e.code()).isTrue();
            seen.add(e.code());
        }
        // Qua được toàn bộ kiểm domain (mã, nhãn, path, khóa, module, độ sâu)
        init.execute(T1);
        assertThat(db).hasSize(DefaultMenus.ENTRIES.size());
    }

    @Test
    void khoaQuyenCuaBoMacDinhDungDinhDangControllerSlashMethodVaCoMucLoiLendingSales() {
        assertThat(DefaultMenus.ENTRIES).extracting(DefaultMenus.Entry::permissionKey)
                .filteredOn(k -> k != null).isNotEmpty().allMatch(k -> KEY_FORMAT.matcher(k).matches());
        assertThat(DefaultMenus.ENTRIES).filteredOn(e -> e.code().equals("admin"))
                .extracting(DefaultMenus.Entry::permissionKey).containsExactly("AccessControlController/listRoles");
        assertThat(DefaultMenus.ENTRIES).filteredOn(e -> e.module() == null).extracting(DefaultMenus.Entry::code)
                .contains("home", "catalog", "admin");
        assertThat(DefaultMenus.ENTRIES).filteredOn(e -> "LENDING".equals(e.module()))
                .extracting(DefaultMenus.Entry::code).startsWith("circulation").allMatch(c -> c.startsWith("circulation"));
        assertThat(DefaultMenus.ENTRIES).filteredOn(e -> "SALES".equals(e.module()))
                .extracting(DefaultMenus.Entry::code).contains("sales", "sales-pos", "sales-orders");
        assertThat(DefaultMenus.ENTRIES).extracting(DefaultMenus.Entry::code).doesNotHaveDuplicates();
    }

    @Test
    void idTatDinhTheoTenantVaCode() {
        assertThat(DefaultMenus.itemsFor(T1)).extracting(MenuItem::id)
                .isEqualTo(DefaultMenus.itemsFor(T1).stream().map(MenuItem::id).toList());
        assertThat(DefaultMenus.itemsFor(T1).get(0).id()).isNotEqualTo(DefaultMenus.itemsFor(T2).get(0).id());
    }

    // ---------- GET /me khi tenant chưa có dữ liệu ----------

    @Test
    void tenantChuaCoDuLieuDungBoMacDinhLocTheoModule() {
        assertThat(codes(getMy.execute(T1, viewer(false, Set.of(), "LENDING"))))
                .containsExactly("home", "catalog", "circulation");
        assertThat(codes(getMy.execute(T1, viewer(false, Set.of(), "SALES")))).containsExactly("home", "catalog");
        // SALES: nhóm "sales" chỉ hiện khi người xem có khóa của ít nhất một con
        assertThat(codes(getMy.execute(T1, viewer(false, Set.of("SalesOrderController/search"), "SALES"))))
                .containsExactly("home", "catalog", "sales");
        List<MenuNode> both = getMy.execute(T1, viewer(true, Set.of(), "LENDING", "SALES"));
        assertThat(codes(both)).containsExactly("home", "catalog", "circulation", "sales", "reports", "admin");
        assertThat(codes(both.get(3).children())).containsExactly("sales-pos", "sales-orders", "sales-contacts",
                "sales-refunds", "sales-stock", "sales-receipts", "sales-suppliers", "sales-audit", "sales-banners",
                "sales-settings");
    }

    @Test
    void locTheoQuyenTrenBoMacDinh() {
        assertThat(codes(getMy.execute(T1, viewer(false, Set.of("AccessControlController/listRoles"), "LENDING"))))
                .contains("admin");
        assertThat(codes(getMy.execute(T1, viewer(false, Set.of(), "LENDING")))).doesNotContain("admin");
    }

    @Test
    void adminBiLocModuleTrenBoMacDinh() {
        assertThat(codes(getMy.execute(T1, viewer(true, Set.of(), "LENDING")))).doesNotContain("sales");
        assertThat(codes(getMy.execute(T1, viewer(true, Set.of())))).containsExactly("home", "catalog", "reports", "admin");
    }

    @Test
    void tenantCoDuLieuRiengThiKhongTronVoiMacDinh() {
        create.execute(T1, new CreateMenuUseCase.Command("mine",
                new MenuDetails("Của tôi", "/mine", null, null, 0, true, null)));
        assertThat(codes(getMy.execute(T1, viewer(true, Set.of(), "LENDING", "SALES")))).containsExactly("mine");
        // tenant khác vẫn dùng mặc định
        assertThat(codes(getMy.execute(T2, viewer(false, Set.of(), "LENDING")))).contains("home", "circulation");
    }

    @Test
    void tokenKhongTenantVanRong() {
        assertThat(getMy.execute(null, viewer(true, Set.of(), "LENDING"))).isEmpty();
    }

    // ---------- danh sách quản trị ----------

    @Test
    void quanTriBaoUsingDefaultsKhiChuaCoDong() {
        assertThat(list.result(T1)).isEqualTo(new ListMenusUseCase.Result(List.of(), true));
        init.execute(T1);
        ListMenusUseCase.Result after = list.result(T1);
        assertThat(after.usingDefaults()).isFalse();
        assertThat(after.items()).hasSize(DefaultMenus.ENTRIES.size());
    }

    // ---------- initializeDefaults ----------

    @Test
    void initializeDefaultsCheoDungMotLanVaPhaCache() {
        List<MenuItem> created = init.execute(T1);
        assertThat(created).hasSize(DefaultMenus.ENTRIES.size());
        assertThat(created).allSatisfy(i -> assertThat(i.tenantId()).isEqualTo(T1));
        assertThat(created).extracting(MenuItem::code).containsExactlyInAnyOrderElementsOf(
                DefaultMenus.ENTRIES.stream().map(DefaultMenus.Entry::code).toList());
        assertThat(evicted).contains(T1);

        MenuItem sales = created.stream().filter(i -> i.code().equals("sales")).findFirst().orElseThrow();
        assertThat(sales.module()).isEqualTo("SALES");
        assertThat(created).filteredOn(i -> i.code().equals("sales-pos")).extracting(MenuItem::parentId)
                .containsExactly(sales.id());
        assertThat(created).filteredOn(i -> i.code().equals("circulation")).extracting(MenuItem::module)
                .containsExactly("LENDING");

        assertThatThrownBy(() -> init.execute(T1)).isInstanceOfSatisfying(MenuException.class,
                e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_ALREADY_INITIALIZED));
        assertThat(db).hasSize(DefaultMenus.ENTRIES.size());
    }

    @Test
    void tenantCoDuLieuBiTuChoiVaKhongAnhHuongTenantKhac() {
        create.execute(T2, new CreateMenuUseCase.Command("x", new MenuDetails("X", "/x", null, null, 0, true, null)));
        init.execute(T1);
        assertThatThrownBy(() -> init.execute(T2)).isInstanceOfSatisfying(MenuException.class,
                e -> assertThat(e.code()).isEqualTo(MenuErrorCode.MENU_ALREADY_INITIALIZED));
        assertThat(db.values().stream().filter(i -> i.tenantId().equals(T2))).hasSize(1);
    }

    @Test
    void sauKhiChepMeVoiAdminDungDuLieuTenantVaVanLocTheoModule() {
        init.execute(T1);
        assertThat(codes(getMy.execute(T1, viewer(true, Set.of(), "LENDING"))))
                .containsExactly("home", "catalog", "circulation", "reports", "admin");
    }
}
