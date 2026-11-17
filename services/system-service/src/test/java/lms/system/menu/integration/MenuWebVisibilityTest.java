package lms.system.menu.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.system.config.SecurityConfiguration;
import lms.system.menu.application.CreateMenuUseCase;
import lms.system.menu.application.DeleteMenuUseCase;
import lms.system.menu.application.GetMyMenuUseCase;
import lms.system.menu.application.InitializeDefaultMenusUseCase;
import lms.system.menu.application.ListMenusUseCase;
import lms.system.menu.application.MenuCachePort;
import lms.system.menu.application.MenuRepositoryPort;
import lms.system.menu.application.UpdateMenuUseCase;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.infrastructure.web.MenuAdminController;
import lms.system.menu.infrastructure.web.MenuController;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Lớp web với use case THẬT (repository/cache giả trong bộ nhớ): claim JWT (admin, permissions, tenantId) ->
 * MenuViewer -> MenuTree.visibleFor, cách ly tenant, phá cache sau khi sửa. Không cần Docker.
 */
@WebMvcTest({MenuController.class, MenuAdminController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        MenuWebVisibilityTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true",
        "lms.security.permission.require-tenant=true"})
class MenuWebVisibilityTest {

    private static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    static final class MemRepo implements MenuRepositoryPort {
        final Map<UUID, MenuItem> items = new HashMap<>();

        @Override
        public List<MenuItem> findAll(UUID tenantId) {
            return items.values().stream().filter(i -> i.tenantId().equals(tenantId)).toList();
        }

        @Override
        public MenuItem save(MenuItem item) {
            MenuItem prev = items.get(item.id());
            MenuItem stored = new MenuItem(item.id(), item.tenantId(), item.parentId(), item.code(), item.label(),
                    item.path(), item.icon(), item.sortOrder(), item.enabled(), item.permissionKey(),
                    prev == null ? 0 : prev.version() + 1, item.createdAt(), item.updatedAt(), item.module());
            items.put(stored.id(), stored);
            return stored;
        }

        @Override
        public void deleteById(UUID tenantId, UUID id) {
            MenuItem i = items.get(id);
            if (i != null && i.tenantId().equals(tenantId)) {
                items.remove(id);
            }
        }
    }

    static final class MemCache implements MenuCachePort {
        final Map<UUID, List<MenuItem>> store = new HashMap<>();
        final List<UUID> evicted = new ArrayList<>();

        @Override
        public Optional<List<MenuItem>> get(UUID tenantId) {
            return Optional.ofNullable(store.get(tenantId));
        }

        @Override
        public void put(UUID tenantId, List<MenuItem> items) {
            store.put(tenantId, List.copyOf(items));
        }

        @Override
        public void evict(UUID tenantId) {
            evicted.add(tenantId);
            store.remove(tenantId);
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean MemRepo memRepo() { return new MemRepo(); }
        @Bean MemCache memCache() { return new MemCache(); }
        @Bean GetMyMenuUseCase getMy(MemRepo r, MemCache c) { return new GetMyMenuUseCase(r, c); }
        @Bean ListMenusUseCase list(MemRepo r) { return new ListMenusUseCase(r); }
        @Bean CreateMenuUseCase create(MemRepo r, MemCache c) {
            return new CreateMenuUseCase(r, c, Clock.fixed(NOW, ZoneOffset.UTC));
        }
        @Bean UpdateMenuUseCase update(MemRepo r, MemCache c) {
            return new UpdateMenuUseCase(r, c, Clock.fixed(NOW, ZoneOffset.UTC));
        }
        @Bean DeleteMenuUseCase del(MemRepo r, MemCache c) { return new DeleteMenuUseCase(r, c); }
        @Bean InitializeDefaultMenusUseCase init(MemRepo r, MemCache c) {
            return new InitializeDefaultMenusUseCase(r, c, Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MemRepo repo;
    @Autowired MemCache cache;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @BeforeEach
    void reset() {
        repo.items.clear();
        cache.store.clear();
        cache.evicted.clear();
    }

    private static RequestPostProcessor user(UUID tenant, String... permissions) {
        return jwt().jwt(j -> j.subject("u").claim("tenantId", tenant.toString())
                        .claim("permissions", List.of(permissions)))
                .authorities(java.util.Arrays.stream(permissions)
                        .map(p -> (GrantedAuthority) new SimpleGrantedAuthority(p)).toList());
    }

    private static RequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> j.subject("a").claim("tenantId", tenant.toString()).claim("admin", true));
    }

    private MenuItem seed(UUID tenant, UUID id, UUID parent, String code, String path, int order, boolean enabled,
                          String key) {
        MenuItem m = new MenuItem(id, tenant, parent, code, "L-" + code, path, null, order, enabled, key, 0, NOW, NOW);
        repo.items.put(id, m);
        return m;
    }

    private MenuItem seed(UUID tenant, String code, String path, int order, boolean enabled, String key) {
        return seed(tenant, UUID.randomUUID(), null, code, path, order, enabled, key);
    }

    @Test
    void mucCoKhoaAnVoiNguoiThieuKhoaVaHienVoiNguoiCoKhoa() throws Exception {
        seed(T1, "home", "/", 1, true, null);
        seed(T1, "secret", "/s", 2, true, "Ctl/m");

        mvc.perform(get("/internal/menus/me").with(user(T1, "Khac/khoa")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("home"));
        mvc.perform(get("/internal/menus/me").with(user(T1, "Ctl/m")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].code").value("secret"));
    }

    @Test
    void tokenKhongCoPermissionsClaimChiThayMucKhongKhoa() throws Exception {
        // Giống token mustChangePassword: không admin, không permissions
        seed(T1, "home", "/", 1, true, null);
        seed(T1, "secret", "/s", 2, true, "Ctl/m");

        mvc.perform(get("/internal/menus/me").with(jwt().jwt(j -> j.subject("u").claim("tenantId", T1.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("home"));
    }

    @Test
    void adminThayMoiMucBatNhungKhongThayMucTat() throws Exception {
        seed(T1, "home", "/", 1, true, null);
        seed(T1, "secret", "/s", 2, true, "Ctl/m");
        seed(T1, "off", "/off", 3, false, null);

        mvc.perform(get("/internal/menus/me").with(admin(T1)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].code").value("secret"));
    }

    @Test
    void nhanhBiTatAnCaCon() throws Exception {
        MenuItem group = seed(T1, UUID.randomUUID(), null, "g", null, 1, false, null);
        seed(T1, UUID.randomUUID(), group.id(), "child", "/c", 1, true, null);

        mvc.perform(get("/internal/menus/me").with(admin(T1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void nutNhomRongAnVaNhomCoConHienThiThiHienKemCon() throws Exception {
        seed(T1, UUID.randomUUID(), null, "empty", null, 1, true, null);
        MenuItem group = seed(T1, UUID.randomUUID(), null, "grp", null, 2, true, null);
        seed(T1, UUID.randomUUID(), group.id(), "locked", "/l", 1, true, "Ctl/locked");
        seed(T1, UUID.randomUUID(), group.id(), "open", "/o", 2, true, null);

        mvc.perform(get("/internal/menus/me").with(user(T1)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("grp"))
                .andExpect(jsonPath("$[0].children.length()").value(1))
                .andExpect(jsonPath("$[0].children[0].code").value("open"));
        mvc.perform(get("/internal/menus/me").with(user(T1, "Ctl/locked")))
                .andExpect(jsonPath("$[0].children.length()").value(2));
    }

    @Test
    void nhomMaMoiConDeuBiKhoaThiAnVoiNguoiThuongNhungHienVoiAdmin() throws Exception {
        MenuItem group = seed(T1, UUID.randomUUID(), null, "grp", null, 1, true, null);
        seed(T1, UUID.randomUUID(), group.id(), "locked", "/l", 1, true, "Ctl/locked");

        mvc.perform(get("/internal/menus/me").with(user(T1))).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/internal/menus/me").with(admin(T1))).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void haiTenantThayMenuRiengKhongLan() throws Exception {
        seed(T1, "t1only", "/a", 1, true, null);
        seed(T2, "t2only", "/b", 1, true, null);

        mvc.perform(get("/internal/menus/me").with(user(T1)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("t1only"));
        mvc.perform(get("/internal/menus/me").with(user(T2)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("t2only"));
        mvc.perform(get("/internal/menus/me").with(user(T1)))
                .andExpect(jsonPath("$[0].code").value("t1only"));
        assertThat(cache.store).containsOnlyKeys(T1, T2);
    }

    @Test
    void quanTriTenantNayKhongThayHayDongVaoMucTenantKhac() throws Exception {
        MenuItem other = seed(T2, "t2only", "/b", 1, true, null);
        seed(T1, "t1only", "/a", 1, true, null);

        mvc.perform(get("/internal/menus").with(admin(T1)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("t1only"));
        mvc.perform(put("/internal/menus/" + other.id()).with(admin(T1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"X\",\"path\":\"/x\",\"sortOrder\":1,\"enabled\":true,\"version\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"));
        mvc.perform(delete("/internal/menus/" + other.id()).with(admin(T1)))
                .andExpect(status().isNotFound());
        assertThat(repo.items).containsKey(other.id());
    }

    @Test
    void cungCodeODuoiHaiTenantDuocPhep() throws Exception {
        String body = "{\"code\":\"home\",\"label\":\"Home\",\"path\":\"/\"}";
        mvc.perform(post("/internal/menus").with(admin(T1)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/internal/menus").with(admin(T2)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/internal/menus").with(admin(T1)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MENU_CODE_DUPLICATED"));
    }

    @Test
    void taoSuaXoaPhaCacheVaMeThayDoiNgay() throws Exception {
        // Tenant chưa có dòng nào: bộ mặc định (ADR-0009 M3), không còn rỗng
        mvc.perform(get("/internal/menus/me").with(user(T1))).andExpect(jsonPath("$.length()").value(3));
        assertThat(cache.store).containsKey(T1);

        String created = mvc.perform(post("/internal/menus").with(admin(T1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"books\",\"label\":\"Sách\",\"path\":\"/catalog\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(cache.evicted).containsExactly(T1);
        mvc.perform(get("/internal/menus/me").with(user(T1))).andExpect(jsonPath("$.length()").value(1));

        String id = com.jayway.jsonpath.JsonPath.read(created, "$.id");
        mvc.perform(put("/internal/menus/" + id).with(admin(T1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Sách\",\"path\":\"/catalog\",\"sortOrder\":1,\"enabled\":false,"
                                + "\"version\":0}"))
                .andExpect(status().isOk());
        assertThat(cache.evicted).hasSize(2);
        mvc.perform(get("/internal/menus/me").with(user(T1))).andExpect(jsonPath("$").isEmpty());

        mvc.perform(delete("/internal/menus/" + id).with(admin(T1))).andExpect(status().isNoContent());
        assertThat(cache.evicted).hasSize(3).containsOnly(T1);
    }

    @Test
    void nguoiThuongKhongSuaDuocDuCoKhoaCuaMethodKhac() throws Exception {
        mvc.perform(post("/internal/menus").with(user(T1, "MenuController/myMenu"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"label\":\"X\",\"path\":\"/x\"}"))
                .andExpect(status().isForbidden());
        assertThat(repo.items).isEmpty();
        assertThat(cache.evicted).isEmpty();
    }

    @Test
    void jsonMeKhongLoCauHinhNoiBo() throws Exception {
        seed(T1, "home", "/", 1, true, "Ctl/m");

        mvc.perform(get("/internal/menus/me").with(admin(T1)))
                .andExpect(jsonPath("$[0].permissionKey").doesNotExist())
                .andExpect(jsonPath("$[0].enabled").doesNotExist())
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].version").doesNotExist());
    }

    // ---------- module (ADR-0009): claim JWT "modules" ----------

    private MenuItem seedModule(UUID tenant, String code, String path, int order, String module) {
        UUID id = UUID.randomUUID();
        MenuItem m = new MenuItem(id, tenant, null, code, "L-" + code, path, null, order, true, null, 0, NOW, NOW,
                module);
        repo.items.put(id, m);
        return m;
    }

    private static RequestPostProcessor withModules(UUID tenant, boolean admin, String... modules) {
        return jwt().jwt(j -> j.subject("m").claim("tenantId", tenant.toString()).claim("admin", admin)
                .claim("modules", List.of(modules)));
    }

    @Test
    void claimModulesLocMenuTheoModuleKeCaAdminVaKhongPhaCache() throws Exception {
        seedModule(T1, "home", "/", 1, null);
        seedModule(T1, "loans", "/loans", 2, "LENDING");
        seedModule(T1, "shop", "/shop", 3, "SALES");

        mvc.perform(get("/internal/menus/me").with(withModules(T1, false, "LENDING")))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].code").value("loans"));
        // Cache đã có cây chưa lọc; đổi claim thì kết quả đổi mà không phá cache
        assertThat(cache.store.get(T1)).hasSize(3);
        mvc.perform(get("/internal/menus/me").with(withModules(T1, false, "SALES")))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].code").value("shop"));
        mvc.perform(get("/internal/menus/me").with(withModules(T1, false, "LENDING", "SALES")))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/internal/menus/me").with(withModules(T1, true)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("home"));
        mvc.perform(get("/internal/menus/me").with(withModules(T1, true, "SALES")))
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(cache.evicted).isEmpty();
    }

    @Test
    void tokenThieuClaimModulesCoiLaLendingKhiCoLegacy() throws Exception {
        seedModule(T1, "home", "/", 1, null);
        seedModule(T1, "loans", "/loans", 2, "LENDING");
        seedModule(T1, "shop", "/shop", 3, "SALES");

        mvc.perform(get("/internal/menus/me").with(user(T1)))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].code").value("loans"));
        mvc.perform(get("/internal/menus/me").with(admin(T1)))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[1].code").value("loans"));
        // Claim có nhưng rỗng thì KHÔNG phải token cũ: chỉ mục lõi
        mvc.perform(get("/internal/menus/me").with(withModules(T1, true)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("home"));
    }

    @Test
    void tenantChuaCoMenuNhanBoMacDinhDaLocTheoModuleVaQuyen() throws Exception {
        mvc.perform(get("/internal/menus/me").with(user(T1)))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("home"))
                .andExpect(jsonPath("$[2].code").value("circulation"));
        mvc.perform(get("/internal/menus/me").with(withModules(T1, true, "LENDING", "SALES")))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[4].code").value("sales"))
                .andExpect(jsonPath("$[4].children.length()").value(5));
        mvc.perform(get("/internal/menus/me").with(withModules(T1, false, "SALES")))
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(repo.items).isEmpty();
    }

    @Test
    void quanTriBaoUsingDefaultsRoiChepMacDinhMotLanLanHaiLa409() throws Exception {
        mvc.perform(get("/internal/menus").with(admin(T1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usingDefaults").value(true))
                .andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(post("/internal/menus/defaults").with(admin(T1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usingDefaults").value(false))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[?(@.code=='circulation')].module").value("LENDING"))
                .andExpect(jsonPath("$.items[?(@.code=='sales-pos')].module").value("SALES"));
        assertThat(repo.items.values()).hasSize(10).allMatch(i -> i.tenantId().equals(T1));
        assertThat(cache.evicted).containsExactly(T1);

        mvc.perform(post("/internal/menus/defaults").with(admin(T1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MENU_ALREADY_INITIALIZED"));
        assertThat(repo.items).hasSize(10);

        mvc.perform(get("/internal/menus").with(admin(T1)))
                .andExpect(jsonPath("$.usingDefaults").value(false))
                .andExpect(jsonPath("$.items.length()").value(10));
        // Sau khi chép: /me dùng dữ liệu của tenant, vẫn lọc theo module
        mvc.perform(get("/internal/menus/me").with(withModules(T1, true, "LENDING")))
                .andExpect(jsonPath("$.length()").value(4));
        // Tenant khác không bị đụng
        mvc.perform(get("/internal/menus").with(admin(T2))).andExpect(jsonPath("$.usingDefaults").value(true));
    }

    @Test
    void khoaQuyenChepMacDinhLaMenuAdminControllerSlashInitializeDefaults() throws Exception {
        mvc.perform(post("/internal/menus/defaults").with(user(T1))).andExpect(status().isForbidden());
        mvc.perform(post("/internal/menus/defaults").with(user(T1, "MenuAdminController/createMenu")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/menus/defaults").with(user(T1, "MenuAdminController/initializeDefaults")))
                .andExpect(status().isCreated());
    }

    @Test
    void taoVaSuaNhanModuleSaiMaBi400() throws Exception {
        mvc.perform(post("/internal/menus").with(admin(T1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"shop\",\"label\":\"Shop\",\"path\":\"/shop\",\"module\":\"SALES\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.module").value("SALES"));
        mvc.perform(post("/internal/menus").with(admin(T1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad\",\"label\":\"B\",\"path\":\"/b\",\"module\":\"HR\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MENU_MODULE_INVALID"));
    }
}
