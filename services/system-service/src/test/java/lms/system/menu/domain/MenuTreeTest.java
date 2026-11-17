package lms.system.menu.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MenuTreeTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final MenuTree tree = new MenuTree(TENANT, List.of());

    private MenuItem add(String code, UUID parent, String path, int sort, boolean enabled, String key) {
        return tree.add(UUID.randomUUID(), code,
                new MenuDetails("Nhãn " + code, path, null, parent, sort, enabled, key), NOW);
    }

    private MenuItem leaf(String code, UUID parent) {
        return add(code, parent, "/" + code, 0, true, null);
    }

    private MenuItem group(String code, UUID parent) {
        return add(code, parent, null, 0, true, null);
    }

    private static MenuDetails details(UUID parent) {
        return new MenuDetails("X", "/x", null, parent, 0, true, null);
    }

    private static MenuErrorCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (MenuException e) {
            return e.code();
        }
        return null;
    }

    // ---------- tạo ----------

    @Test
    void taoMucGocThanhCong() {
        MenuItem item = leaf("home", null);
        assertThat(item.tenantId()).isEqualTo(TENANT);
        assertThat(item.version()).isZero();
        assertThat(item.createdAt()).isEqualTo(NOW);
        assertThat(tree.size()).isEqualTo(1);
    }

    @Test
    void codeTrungTrongTenantBiTuChoi() {
        leaf("home", null);
        assertThat(codeOf(() -> leaf("home", null))).isEqualTo(MenuErrorCode.MENU_CODE_DUPLICATED);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "co khoang trang", "-bat-dau-bang-gach", "a/b"})
    void codeKhongHopLe(String code) {
        assertThat(codeOf(() -> tree.add(UUID.randomUUID(), code, details(null), NOW)))
                .isEqualTo(MenuErrorCode.MENU_INVALID);
    }

    @Test
    void codeQuaDaiBiTuChoi() {
        assertThat(codeOf(() -> tree.add(UUID.randomUUID(), "a".repeat(101), details(null), NOW)))
                .isEqualTo(MenuErrorCode.MENU_INVALID);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void nhanBatBuoc(String label) {
        MenuDetails d = new MenuDetails(label, "/x", null, null, 0, true, null);
        assertThat(codeOf(() -> tree.add(UUID.randomUUID(), "x", d, NOW))).isEqualTo(MenuErrorCode.MENU_INVALID);
    }

    @Test
    void nhanVaIconQuaDaiBiTuChoi() {
        MenuDetails longLabel = new MenuDetails("a".repeat(201), "/x", null, null, 0, true, null);
        MenuDetails longIcon = new MenuDetails("X", "/x", "i".repeat(101), null, 0, true, null);
        MenuDetails blankIcon = new MenuDetails("X", "/x", " ", null, 0, true, null);
        assertThat(codeOf(() -> tree.add(UUID.randomUUID(), "a", longLabel, NOW))).isEqualTo(MenuErrorCode.MENU_INVALID);
        assertThat(codeOf(() -> tree.add(UUID.randomUUID(), "b", longIcon, NOW))).isEqualTo(MenuErrorCode.MENU_INVALID);
        assertThat(codeOf(() -> tree.add(UUID.randomUUID(), "c", blankIcon, NOW))).isEqualTo(MenuErrorCode.MENU_INVALID);
    }

    @Test
    void chaKhongTonTai() {
        assertThat(codeOf(() -> leaf("a", UUID.randomUUID()))).isEqualTo(MenuErrorCode.MENU_PARENT_NOT_FOUND);
    }

    @Test
    void nutNhomKhongCanPathNhungPhaiCoNhan() {
        MenuItem g = group("admin", null);
        assertThat(g.isGroup()).isTrue();
    }

    // ---------- path ----------

    @ParameterizedTest
    @ValueSource(strings = {"/", "/catalog", "/admin/menus", "/a/b?x=1#h"})
    void pathNoiBoHopLe(String path) {
        assertThat(codeOf(() -> add("p" + path.hashCode(), null, path, 0, true, null))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "catalog", "//evil.com", "///x", "http://evil.com", "https://evil.com/x", "javascript:alert(1)",
            "data:text/html,x", " /x", "/x y", "/x\ty", "/x\ny", "/a\\b", "/\\evil.com", ""})
    void pathKhongPhaiRouteNoiBoBiTuChoi(String path) {
        assertThat(codeOf(() -> add("p", null, path, 0, true, null))).isEqualTo(MenuErrorCode.MENU_PATH_INVALID);
    }

    @Test
    void pathQuaDaiBiTuChoi() {
        assertThat(codeOf(() -> add("p", null, "/" + "a".repeat(500), 0, true, null)))
                .isEqualTo(MenuErrorCode.MENU_PATH_INVALID);
        assertThat(codeOf(() -> add("q", null, "/" + "a".repeat(499), 0, true, null))).isNull();
    }

    // ---------- permission key ----------

    @ParameterizedTest
    @ValueSource(strings = {"AccessControlController/listRoles", "MenuAdminController/createMenu", "custom:key.v1"})
    void khoaQuyenHopLe(String key) {
        assertThat(codeOf(() -> add("k", null, "/k", 0, true, key))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a b", "A/b/c", "/x", "x/", "a;b"})
    void khoaQuyenKhongHopLe(String key) {
        assertThat(codeOf(() -> add("k", null, "/k", 0, true, key)))
                .isEqualTo(MenuErrorCode.MENU_PERMISSION_KEY_INVALID);
    }

    // ---------- độ sâu ----------

    @Test
    void sauToiDaBaCap() {
        MenuItem l1 = group("l1", null);
        MenuItem l2 = group("l2", l1.id());
        MenuItem l3 = leaf("l3", l2.id());
        assertThat(l3.parentId()).isEqualTo(l2.id());
        assertThat(codeOf(() -> leaf("l4", l3.id()))).isEqualTo(MenuErrorCode.MENU_TOO_DEEP);
    }

    @Test
    void diChuyenNhanhLamVuotDoSauBiTuChoi() {
        MenuItem a = group("a", null);
        MenuItem b = group("b", a.id());
        MenuItem c = leaf("c", b.id());           // a > b > c (3 cấp)
        MenuItem x = group("x", null);
        leaf("y", x.id());                          // x > y
        // chuyển x (cao 2) vào b (sâu 2) => 4 cấp
        assertThat(codeOf(() -> tree.update(x.id(), details(b.id()), 0, NOW))).isEqualTo(MenuErrorCode.MENU_TOO_DEEP);
        // chuyển lá y... chuyển x vào a (sâu 1) => 3 cấp, được
        assertThat(codeOf(() -> tree.update(x.id(), details(a.id()), 0, NOW))).isNull();
        assertThat(c.parentId()).isEqualTo(b.id());
    }

    // ---------- chu trình ----------

    @Test
    void khongDuocLamChaCuaChinhNo() {
        MenuItem a = leaf("a", null);
        assertThat(codeOf(() -> tree.update(a.id(), details(a.id()), 0, NOW))).isEqualTo(MenuErrorCode.MENU_CYCLE);
    }

    @Test
    void khongDuocChuyenVaoConChau() {
        MenuItem a = group("a", null);
        MenuItem b = group("b", a.id());
        assertThat(codeOf(() -> tree.update(a.id(), details(b.id()), 0, NOW))).isEqualTo(MenuErrorCode.MENU_CYCLE);
    }

    @Test
    void chuTrinhDuocBaoTruocDoSau() {
        MenuItem a = group("a", null);
        MenuItem b = group("b", a.id());
        MenuItem c = leaf("c", b.id());
        // a vào c vừa tạo chu trình vừa quá sâu: phải báo chu trình
        assertThat(codeOf(() -> tree.update(a.id(), details(c.id()), 0, NOW))).isEqualTo(MenuErrorCode.MENU_CYCLE);
    }

    @Test
    void dulieuDaCoChuTrinhKhongLapVoHan() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        MenuItem ia = new MenuItem(a, TENANT, b, "a", "A", "/a", null, 0, true, null, 0, NOW, NOW);
        MenuItem ib = new MenuItem(b, TENANT, a, "b", "B", "/b", null, 0, true, null, 0, NOW, NOW);
        MenuTree broken = new MenuTree(TENANT, List.of(ia, ib));
        assertThat(codeOf(() -> broken.add(UUID.randomUUID(), "c",
                new MenuDetails("C", "/c", null, a, 0, true, null), NOW))).isEqualTo(MenuErrorCode.MENU_CYCLE);
        assertThat(broken.visibleFor(new MenuViewer(true, Set.of()))).isEmpty();
    }

    // ---------- sửa ----------

    @Test
    void suaDoiTruongKhongDoiCodeVaGiuVersion() {
        MenuItem a = leaf("a", null);
        Instant later = NOW.plusSeconds(60);
        MenuItem updated = tree.update(a.id(),
                new MenuDetails("Mới", "/moi", "icon", null, 5, false, "C/m"), 0, later);
        assertThat(updated.code()).isEqualTo("a");
        assertThat(updated.label()).isEqualTo("Mới");
        assertThat(updated.path()).isEqualTo("/moi");
        assertThat(updated.sortOrder()).isEqualTo(5);
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.createdAt()).isEqualTo(NOW);
        assertThat(updated.updatedAt()).isEqualTo(later);
        assertThat(updated.version()).isZero();
    }

    @Test
    void suaSaiVersionLaXungDot() {
        MenuItem a = leaf("a", null);
        assertThat(codeOf(() -> tree.update(a.id(), details(null), 7, NOW))).isEqualTo(MenuErrorCode.MENU_VERSION_CONFLICT);
    }

    @Test
    void suaMucKhongTonTai() {
        assertThat(codeOf(() -> tree.update(UUID.randomUUID(), details(null), 0, NOW)))
                .isEqualTo(MenuErrorCode.MENU_NOT_FOUND);
    }

    @Test
    void suaChaKhongTonTai() {
        MenuItem a = leaf("a", null);
        assertThat(codeOf(() -> tree.update(a.id(), details(UUID.randomUUID()), 0, NOW)))
                .isEqualTo(MenuErrorCode.MENU_PARENT_NOT_FOUND);
    }

    @Test
    void suaDuLieuSaiBiTuChoiVaKhongThayDoiCay() {
        MenuItem a = leaf("a", null);
        MenuDetails bad = new MenuDetails("X", "http://evil.com", null, null, 0, true, null);
        assertThat(codeOf(() -> tree.update(a.id(), bad, 0, NOW))).isEqualTo(MenuErrorCode.MENU_PATH_INVALID);
        assertThat(tree.find(a.id()).path()).isEqualTo("/a");
    }

    @Test
    void doiChaThanhGocLaDiChuyen() {
        MenuItem g = group("g", null);
        MenuItem a = leaf("a", g.id());
        MenuItem moved = tree.update(a.id(), details(null), 0, NOW);
        assertThat(moved.parentId()).isNull();
    }

    // ---------- xóa ----------

    @Test
    void xoaNutConConBiTuChoi() {
        MenuItem g = group("g", null);
        leaf("a", g.id());
        assertThat(codeOf(() -> tree.remove(g.id()))).isEqualTo(MenuErrorCode.MENU_HAS_CHILDREN);
        assertThat(tree.size()).isEqualTo(2);
    }

    @Test
    void xoaLaThanhCongRoiXoaCha() {
        MenuItem g = group("g", null);
        MenuItem a = leaf("a", g.id());
        assertThat(tree.remove(a.id()).id()).isEqualTo(a.id());
        assertThat(tree.remove(g.id()).id()).isEqualTo(g.id());
        assertThat(tree.size()).isZero();
    }

    @Test
    void xoaMucKhongTonTai() {
        assertThat(codeOf(() -> tree.remove(UUID.randomUUID()))).isEqualTo(MenuErrorCode.MENU_NOT_FOUND);
    }

    // ---------- giới hạn ----------

    @Test
    void toiDa200MucMoiTenant() {
        List<MenuItem> existing = new ArrayList<>();
        for (int i = 0; i < MenuTree.MAX_ITEMS; i++) {
            existing.add(new MenuItem(UUID.randomUUID(), TENANT, null, "c" + i, "L" + i, "/c" + i, null, 0, true,
                    null, 0, NOW, NOW));
        }
        MenuTree full = new MenuTree(TENANT, existing);
        assertThat(codeOf(() -> full.add(UUID.randomUUID(), "extra", details(null), NOW)))
                .isEqualTo(MenuErrorCode.MENU_LIMIT_EXCEEDED);
    }

    // ---------- hiển thị ----------

    private static MenuViewer user(String... keys) {
        return new MenuViewer(false, Set.of(keys));
    }

    private static List<String> codes(List<MenuNode> nodes) {
        return nodes.stream().map(n -> n.item().code()).toList();
    }

    @Test
    void mucTatAnCaNhanh() {
        MenuItem g = add("g", null, null, 0, false, null);
        leaf("child", g.id());
        leaf("other", null);
        assertThat(codes(tree.visibleFor(user()))).containsExactly("other");
        assertThat(codes(tree.visibleFor(new MenuViewer(true, Set.of())))).containsExactly("other");
    }

    @Test
    void conBiTatThiChaKhongBiAnNeuConLaLa() {
        MenuItem parentLeaf = leaf("parent", null);
        add("child", parentLeaf.id(), "/child", 0, false, null);
        List<MenuNode> out = tree.visibleFor(user());
        assertThat(codes(out)).containsExactly("parent");
        assertThat(out.get(0).children()).isEmpty();
    }

    @Test
    void mucCoKhoaChiHienKhiAdminHoacCoKhoa() {
        add("admin", null, "/admin", 0, true, "AccessControlController/listRoles");
        assertThat(codes(tree.visibleFor(user()))).isEmpty();
        assertThat(codes(tree.visibleFor(user("Khac/khoa")))).isEmpty();
        assertThat(codes(tree.visibleFor(user("AccessControlController/listRoles")))).containsExactly("admin");
        assertThat(codes(tree.visibleFor(new MenuViewer(true, Set.of())))).containsExactly("admin");
    }

    @Test
    void adminKhongBypassMucDangTat() {
        add("off", null, "/off", 0, false, "A/b");
        assertThat(tree.visibleFor(new MenuViewer(true, Set.of()))).isEmpty();
    }

    @Test
    void mucKhongKhoaMoiNguoiDangNhapDeuThay() {
        leaf("home", null);
        assertThat(codes(tree.visibleFor(user()))).containsExactly("home");
        assertThat(codes(tree.visibleFor(new MenuViewer(false, null)))).containsExactly("home");
    }

    @Test
    void khoaCuaChaAnCaNhanhKeCaConKhongKhoa() {
        MenuItem g = add("g", null, null, 0, true, "A/b");
        leaf("child", g.id());
        assertThat(tree.visibleFor(user())).isEmpty();
        assertThat(codes(tree.visibleFor(user("A/b")))).containsExactly("g");
    }

    @Test
    void nutNhomChiHienKhiConHienConLaCoKhoa() {
        MenuItem g = group("g", null);
        add("secret", g.id(), "/secret", 0, true, "A/b");
        // không có quyền: con ẩn => nhóm ẩn
        assertThat(tree.visibleFor(user())).isEmpty();
        // có quyền: nhóm hiện cùng con
        List<MenuNode> out = tree.visibleFor(user("A/b"));
        assertThat(codes(out)).containsExactly("g");
        assertThat(codes(out.get(0).children())).containsExactly("secret");
    }

    @Test
    void nutNhomRongAn() {
        group("empty", null);
        assertThat(tree.visibleFor(new MenuViewer(true, Set.of()))).isEmpty();
    }

    @Test
    void nhomLongNhauAnKhiLaRong() {
        MenuItem g1 = group("g1", null);
        MenuItem g2 = group("g2", g1.id());
        add("deep", g2.id(), "/deep", 0, false, null);
        assertThat(tree.visibleFor(user())).isEmpty();
    }

    @Test
    void sapTheoSortOrderRoiLabel() {
        tree.add(UUID.randomUUID(), "c", new MenuDetails("Cam", "/c", null, null, 1, true, null), NOW);
        tree.add(UUID.randomUUID(), "b", new MenuDetails("banh", "/b", null, null, 1, true, null), NOW);
        tree.add(UUID.randomUUID(), "a", new MenuDetails("Zeta", "/a", null, null, 0, true, null), NOW);
        tree.add(UUID.randomUUID(), "d", new MenuDetails("Anh", "/d", null, null, 1, true, null), NOW);
        assertThat(codes(tree.visibleFor(user()))).containsExactly("a", "d", "b", "c");
    }

    @Test
    void orderedDuyetChaTruocConKeCaMucTat() {
        MenuItem g = add("g", null, null, 0, false, null);
        MenuItem a = leaf("a", g.id());
        MenuItem z = tree.add(UUID.randomUUID(), "z", new MenuDetails("Z", "/z", null, null, 9, true, null), NOW);
        assertThat(tree.ordered()).extracting(MenuItem::id).containsExactly(g.id(), a.id(), z.id());
    }

    @Test
    void mucMoCoiBoQuaKhiHienThi() {
        MenuItem orphan = new MenuItem(UUID.randomUUID(), TENANT, UUID.randomUUID(), "o", "O", "/o", null, 0, true,
                null, 0, NOW, NOW);
        MenuTree t = new MenuTree(TENANT, List.of(orphan));
        assertThat(t.visibleFor(user())).isEmpty();
    }

    @Test
    void viewerSaoChepTapQuyen() {
        Set<String> mutable = new java.util.HashSet<>(Set.of("A/b"));
        MenuViewer viewer = new MenuViewer(false, mutable);
        mutable.clear();
        assertThat(viewer.canSee("A/b")).isTrue();
        assertThat(viewer.permissions()).hasSize(1);
    }
    // ---------- module (ADR-0009) ----------

    private MenuItem moduleItem(String code, UUID parent, String path, String module, String key) {
        return tree.add(UUID.randomUUID(), code, new MenuDetails("Nhãn " + code, path, null, parent, 0, true, key,
                module), NOW);
    }

    @Test
    void mucLoiModuleNullLuonHienKeCaKhiViewerKhongCoModuleNao() {
        leaf("home", null);
        assertThat(codes(tree.visibleFor(new MenuViewer(false, Set.of(), Set.of())))).containsExactly("home");
        assertThat(codes(tree.visibleFor(new MenuViewer(false, Set.of())))).containsExactly("home");
    }

    @Test
    void mucCoModuleChiHienKhiViewerCoModuleDo() {
        leaf("home", null);
        moduleItem("loans", null, "/loans", "LENDING", null);
        moduleItem("shop", null, "/shop", "SALES", null);

        assertThat(codes(tree.visibleFor(new MenuViewer(false, Set.of(), Set.of("LENDING")))))
                .containsExactlyInAnyOrder("home", "loans");
        assertThat(codes(tree.visibleFor(new MenuViewer(false, Set.of(), Set.of("SALES")))))
                .containsExactlyInAnyOrder("home", "shop");
        assertThat(codes(tree.visibleFor(new MenuViewer(false, Set.of(), Set.of("LENDING", "SALES")))))
                .containsExactlyInAnyOrder("home", "loans", "shop");
    }

    @Test
    void adminCungBiLocTheoModule() {
        moduleItem("loans", null, "/loans", "LENDING", null);
        moduleItem("shop", null, "/shop", "SALES", "Sales/x");

        assertThat(codes(tree.visibleFor(new MenuViewer(true, Set.of(), Set.of("LENDING"))))).containsExactly("loans");
        assertThat(tree.visibleFor(new MenuViewer(true, Set.of(), Set.of()))).isEmpty();
    }

    @Test
    void nhanhModuleTatAnCaConKeCaConKhongGanModule() {
        MenuItem sales = moduleItem("sales", null, null, "SALES", null);
        leaf("kid-core", sales.id());
        moduleItem("kid-sales", sales.id(), "/k", "SALES", null);

        assertThat(tree.visibleFor(new MenuViewer(true, Set.of(), Set.of("LENDING")))).isEmpty();
        List<MenuNode> on = tree.visibleFor(new MenuViewer(false, Set.of(), Set.of("SALES")));
        assertThat(codes(on)).containsExactly("sales");
        assertThat(codes(on.get(0).children())).containsExactlyInAnyOrder("kid-core", "kid-sales");
    }

    @Test
    void conThuocModuleTatThiNhomRongBiCatNhungMucLoiCungCapVanHien() {
        MenuItem group = group("grp", null);
        moduleItem("only-sales", group.id(), "/s", "SALES", null);
        MenuViewer lendingOnly = new MenuViewer(false, Set.of(), Set.of("LENDING"));
        assertThat(tree.visibleFor(lendingOnly)).isEmpty();
        leaf("core", group.id());
        assertThat(codes(tree.visibleFor(lendingOnly).get(0).children())).containsExactly("core");
    }

    @Test
    void khongDoiModuleKhongPhaCayDeLocTheoClaimMoiLanXem() {
        moduleItem("shop", null, "/shop", "SALES", null);
        assertThat(tree.visibleFor(new MenuViewer(false, Set.of(), Set.of()))).isEmpty();
        assertThat(tree.visibleFor(new MenuViewer(false, Set.of(), Set.of("SALES")))).hasSize(1);
    }

    @Test
    void moduleSaiMaBiMenuModuleInvalidKhiTaoVaSua() {
        assertThat(codeOf(() -> moduleItem("x", null, "/x", "HR", null))).isEqualTo(MenuErrorCode.MENU_MODULE_INVALID);
        assertThat(codeOf(() -> moduleItem("y", null, "/y", "lending", null)))
                .isEqualTo(MenuErrorCode.MENU_MODULE_INVALID);
        MenuItem ok = leaf("ok", null);
        assertThat(codeOf(() -> tree.update(ok.id(),
                new MenuDetails("X", "/x", null, null, 0, true, null, "BAD"), 0, NOW)))
                .isEqualTo(MenuErrorCode.MENU_MODULE_INVALID);
    }

    @Test
    void suaDatVaXoaModule() {
        MenuItem item = leaf("m", null);
        assertThat(item.module()).isNull();
        MenuItem set = tree.update(item.id(), new MenuDetails("M", "/m", null, null, 0, true, null, "SALES"), 0, NOW);
        assertThat(set.module()).isEqualTo("SALES");
        MenuItem cleared = tree.update(item.id(), new MenuDetails("M", "/m", null, null, 0, true, null, null), 0, NOW);
        assertThat(cleared.module()).isNull();
    }

    @Test
    void viewerSaoChepTapModule() {
        Set<String> mutable = new java.util.HashSet<>(Set.of("SALES"));
        MenuViewer viewer = new MenuViewer(false, Set.of(), mutable);
        mutable.clear();
        assertThat(viewer.hasModule("SALES")).isTrue();
        assertThat(viewer.hasModule(null)).isTrue();
        assertThat(viewer.hasModule("LENDING")).isFalse();
    }
}
