package lms.system.menu.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.security.permission.PermissionKeyRegistrar;
import lms.system.menu.application.CreateMenuUseCase;
import lms.system.menu.application.MenuRepositoryPort;
import lms.system.menu.domain.MenuDetails;
import lms.system.menu.domain.MenuErrorCode;
import lms.system.menu.domain.MenuException;
import lms.system.menu.domain.MenuItem;
import lms.system.menu.infrastructure.cache.RedisMenuCacheAdapter;
import lms.tenant.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy: mvn -pl services/system-service -am test -Dtest=MenuInfrastructureIT
 * -Dsurefire.failIfNoSpecifiedTests=false (Docker 29 thêm -DargLine=-Dapi.version=1.44).
 *
 * <p>Postgres + Redis thật. Flyway chạy bằng superuser của container (chủ bảng, V1 + afterMigrate của system),
 * ứng dụng kết nối bằng vai trò system_app (NOSUPERUSER NOBYPASSRLS) nên chịu RLS như môi trường thật. Kiểm: CRUD
 * theo tenant, cách ly RLS, khóa lạc quan, cache trúng/phá sau commit/không phá khi rollback, và Redis chết vẫn đọc
 * được từ DB (test cuối cùng vì dừng container Redis).
 */
@SpringBootTest(properties = {
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "migration.run-on-startup=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json"})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MenuInfrastructureIT {

    private static final String APP_ROLE = "system_app";
    private static final UUID DEFAULT_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE + "' NOSUPERUSER NOBYPASSRLS");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/system").load().migrate();
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> APP_ROLE);
        r.add("spring.datasource.password", () -> APP_ROLE);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired MenuRepositoryPort repository;
    @Autowired CreateMenuUseCase createMenu;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    // ---------- tiện ích ----------

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), APP_ROLE, APP_ROLE);
    }

    private static RequestPostProcessor admin(UUID tenant) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", tenant.toString())
                .claim("admin", true));
    }

    private static RequestPostProcessor user(UUID tenant, String... permissions) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", tenant.toString())
                        .claim("permissions", List.of(permissions)))
                .authorities(java.util.Arrays.stream(permissions)
                        .map(p -> (org.springframework.security.core.GrantedAuthority) new org.springframework.security.core.authority.SimpleGrantedAuthority(p))
                        .toList());
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private JsonNode create(UUID tenant, String code, String path, UUID parent) throws Exception {
        String parentJson = parent == null ? "null" : "\"" + parent + "\"";
        return body(mvc.perform(post("/internal/menus").with(admin(tenant)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"label\":\"Nhãn " + code + "\",\"path\":"
                                + (path == null ? "null" : "\"" + path + "\"") + ",\"parentId\":" + parentJson + "}"))
                .andExpect(status().isCreated()));
    }

    private static String cacheKey(UUID tenant) {
        return RedisMenuCacheAdapter.key(tenant);
    }

    private static void execSuper(String sql) throws SQLException {
        try (Connection c = superuser(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void insertMenuAsSuper(UUID tenant, String code) throws SQLException {
        try (Connection c = superuser(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO menus (id, tenant_id, code, label, path) VALUES (?, ?, ?, ?, ?)")) {
            p.setObject(1, UUID.randomUUID());
            p.setObject(2, tenant);
            p.setString(3, code);
            p.setString(4, "Nhãn " + code);
            p.setString(5, "/" + code);
            p.executeUpdate();
        }
    }

    // ---------- seed + CRUD ----------

    @Test
    @Order(1)
    void seedMacDinhHienChoNguoiThuongVaAnMucCoKhoa() throws Exception {
        mvc.perform(get("/internal/menus/me").with(user(DEFAULT_TENANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("home"))
                .andExpect(jsonPath("$[1].code").value("catalog"))
                .andExpect(jsonPath("$[2].code").value("circulation"));
        // Admin và người có đúng khóa thấy thêm mục Quản trị
        mvc.perform(get("/internal/menus/me").with(admin(DEFAULT_TENANT)))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].code").value("admin"));
        mvc.perform(get("/internal/menus/me").with(user(DEFAULT_TENANT, "AccessControlController/listRoles")))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    @Order(2)
    void crudTheoTenantVoiCayChaCon() throws Exception {
        UUID t = UUID.randomUUID();
        JsonNode group = create(t, "grp", null, null);
        JsonNode child = create(t, "kid", "/kid", UUID.fromString(group.get("id").asText()));
        assertThat(group.get("version").asLong()).isZero();
        assertThat(child.get("parentId").asText()).isEqualTo(group.get("id").asText());

        // /me: nhóm có con hiện, lồng đúng
        mvc.perform(get("/internal/menus/me").with(user(t)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("grp"))
                .andExpect(jsonPath("$[0].children[0].code").value("kid"));

        // Danh sách quản trị: cha trước con
        mvc.perform(get("/internal/menus").with(admin(t)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("grp"))
                .andExpect(jsonPath("$[1].code").value("kid"));

        // Sửa: version tăng, tắt mục thì ẩn khỏi /me
        String childId = child.get("id").asText();
        JsonNode updated = body(mvc.perform(put("/internal/menus/" + childId).with(admin(t))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Mới\",\"path\":\"/kid\",\"parentId\":\"" + group.get("id").asText()
                                + "\",\"sortOrder\":1,\"enabled\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Mới")));
        assertThat(updated.get("version").asLong()).isEqualTo(1);
        mvc.perform(get("/internal/menus/me").with(user(t))).andExpect(jsonPath("$.length()").value(0));

        // Xóa cha còn con: 409; xóa con rồi xóa cha: 204
        mvc.perform(delete("/internal/menus/" + group.get("id").asText()).with(admin(t)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MENU_HAS_CHILDREN"));
        mvc.perform(delete("/internal/menus/" + childId).with(admin(t))).andExpect(status().isNoContent());
        mvc.perform(delete("/internal/menus/" + group.get("id").asText()).with(admin(t)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/internal/menus").with(admin(t))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(3)
    void trungMaTrongCungTenantBi409NhungKhacTenantThiDuoc() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        create(a, "dup", "/dup", null);
        mvc.perform(post("/internal/menus").with(admin(a)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"dup\",\"label\":\"X\",\"path\":\"/x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MENU_CODE_DUPLICATED"));
        create(b, "dup", "/dup", null);
    }

    // ---------- cách ly tenant ----------

    @Test
    @Order(4)
    void cachLyTenantBangHibernateTenantIdVaRlsCuaVaiTroSystemApp() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        insertMenuAsSuper(a, "of-a");
        insertMenuAsSuper(b, "of-b");

        // Tầng ứng dụng: mỗi tenant chỉ thấy mục của mình
        mvc.perform(get("/internal/menus").with(admin(a)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("of-a"));
        mvc.perform(get("/internal/menus").with(admin(b)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("of-b"));

        // Sửa/xóa mục của tenant khác: 404 (không lộ sự tồn tại)
        String bId = body(mvc.perform(get("/internal/menus").with(admin(b)))).get(0).get("id").asText();
        mvc.perform(put("/internal/menus/" + bId).with(admin(a)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Hack\",\"path\":\"/h\",\"sortOrder\":1,\"enabled\":true,\"version\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/internal/menus/" + bId).with(admin(a))).andExpect(status().isNotFound());

        // Tầng DB (RLS) bằng chính vai trò system_app, không qua Hibernate
        try (Connection c = appConnection(); Statement s = c.createStatement()) {
            // Không có app.tenant_id: fail-closed, không thấy dòng nào
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM menus")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            s.execute("SELECT set_config('app.tenant_id', '" + a + "', false)");
            try (ResultSet rs = s.executeQuery("SELECT code FROM menus")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("of-a");
                assertThat(rs.next()).isFalse();
            }
            // Ghi dòng của tenant khác bị WITH CHECK chặn
            assertThatThrownBy(() -> s.execute("INSERT INTO menus (id, tenant_id, code, label) VALUES ('"
                    + UUID.randomUUID() + "', '" + b + "', 'evil', 'Evil')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            // UPDATE dòng của tenant khác: 0 dòng bị ảnh hưởng
            assertThat(s.executeUpdate("UPDATE menus SET label = 'x' WHERE code = 'of-b'")).isZero();
        }
    }

    @Test
    @Order(5)
    void khoaNgoaiKepChanChaThuocTenantKhac() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        insertMenuAsSuper(b, "parent-of-b");
        UUID bParent;
        try (Connection c = superuser(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id FROM menus WHERE code = 'parent-of-b'")) {
            rs.next();
            bParent = rs.getObject(1, UUID.class);
        }
        // Domain đã chặn bằng MENU_PARENT_NOT_FOUND (cha không thuộc cây của tenant a)
        mvc.perform(post("/internal/menus").with(admin(a)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"orphan\",\"label\":\"X\",\"path\":\"/o\",\"parentId\":\"" + bParent + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MENU_PARENT_NOT_FOUND"));
        // Và DB chặn nếu ai đó bỏ qua domain: FK kép (parent_id, tenant_id)
        assertThatThrownBy(() -> execSuper("INSERT INTO menus (id, tenant_id, parent_id, code, label, path) VALUES ('"
                + UUID.randomUUID() + "', '" + a + "', '" + bParent + "', 'orphan2', 'X', '/o')"))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_menus_parent_tenant");
    }

    // ---------- khóa lạc quan ----------

    @Test
    @Order(6)
    void phienBanCuBi409VersionConflict() throws Exception {
        UUID t = UUID.randomUUID();
        String id = create(t, "ver", "/ver", null).get("id").asText();
        String body = "{\"label\":\"A\",\"path\":\"/ver\",\"sortOrder\":1,\"enabled\":true,\"version\":%d}";
        mvc.perform(put("/internal/menus/" + id).with(admin(t)).contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(0))).andExpect(status().isOk());
        mvc.perform(put("/internal/menus/" + id).with(admin(t)).contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MENU_VERSION_CONFLICT"));
    }

    @Test
    @Order(7)
    void haiTransactionSuaCungMucThiNguoiSauBiMenuVersionConflictTuHibernate() {
        UUID t = UUID.randomUUID();
        MenuItem created = TenantContext.callAs(t, () -> inTx(() -> createMenu.execute(t,
                new CreateMenuUseCase.Command("race", new MenuDetails("R", "/race", null, null, 0, true, null)))));

        MenuException ex = TenantContext.callAs(t, () -> {
            TransactionTemplate outer = new TransactionTemplate(txManager);
            return org.junit.jupiter.api.Assertions.assertThrows(MenuException.class, () -> outer.executeWithoutResult(st -> {
                // tx1 nạp mục (version 0) vào persistence context
                MenuItem loaded = repository.findAll(t).get(0);
                // tx2 (độc lập) sửa và commit trước
                TransactionTemplate other = new TransactionTemplate(txManager,
                        withRequiresNew());
                other.executeWithoutResult(s2 -> repository.save(withLabel(repository.findAll(t).get(0), "Từ tx2")));
                // tx1 ghi trên bản cũ: Hibernate @Version phát hiện, adapter đổi thành MENU_VERSION_CONFLICT
                repository.save(withLabel(loaded, "Từ tx1"));
            }));
        });
        assertThat(ex.code()).isEqualTo(MenuErrorCode.MENU_VERSION_CONFLICT);

        MenuItem after = TenantContext.callAs(t, () -> inTx(() -> repository.findAll(t).get(0)));
        assertThat(after.label()).isEqualTo("Từ tx2");
        assertThat(after.version()).isEqualTo(created.version() + 1);
    }

    private static MenuItem withLabel(MenuItem i, String label) {
        return new MenuItem(i.id(), i.tenantId(), i.parentId(), i.code(), label, i.path(), i.icon(), i.sortOrder(),
                i.enabled(), i.permissionKey(), i.version(), i.createdAt(), java.time.Instant.now());
    }

    private static org.springframework.transaction.support.DefaultTransactionDefinition withRequiresNew() {
        var def = new org.springframework.transaction.support.DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return def;
    }

    private <T> T inTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(txManager).execute(s -> action.get());
    }

    // ---------- cache ----------

    @Test
    @Order(8)
    void cacheTrungThiKhongDocDbVaPhaSauCommit() throws Exception {
        UUID t = UUID.randomUUID();
        create(t, "c1", "/c1", null);
        assertThat(redisTemplate.hasKey(cacheKey(t))).isFalse();

        // Trượt: đọc DB rồi SET kèm TTL tối đa 10 phút
        mvc.perform(get("/internal/menus/me").with(user(t))).andExpect(jsonPath("$.length()").value(1));
        assertThat(redisTemplate.hasKey(cacheKey(t))).isTrue();
        Long ttl = redisTemplate.getExpire(cacheKey(t));
        assertThat(ttl).isBetween(1L, 600L);

        // Sửa thẳng DB (bỏ qua ứng dụng): /me vẫn trả bản cache => chứng minh trúng cache
        execSuper("INSERT INTO menus (id, tenant_id, code, label, path) VALUES ('" + UUID.randomUUID() + "', '" + t
                + "', 'sneaky', 'Sneaky', '/sneaky')");
        mvc.perform(get("/internal/menus/me").with(user(t))).andExpect(jsonPath("$.length()").value(1));

        // Ghi qua API: phá cache sau commit, lần đọc kế tiếp thấy cả hai mục
        create(t, "c2", "/c2", null);
        assertThat(redisTemplate.hasKey(cacheKey(t))).isFalse();
        mvc.perform(get("/internal/menus/me").with(user(t))).andExpect(jsonPath("$.length()").value(3));
        assertThat(redisTemplate.hasKey(cacheKey(t))).isTrue();

        // Cache của tenant khác không bị đụng
        UUID other = UUID.randomUUID();
        create(other, "o1", "/o1", null);
        mvc.perform(get("/internal/menus/me").with(user(other)));
        assertThat(redisTemplate.hasKey(cacheKey(other))).isTrue();
        create(t, "c3", "/c3", null);
        assertThat(redisTemplate.hasKey(cacheKey(other))).isTrue();
    }

    @Test
    @Order(9)
    void evictHoanToiSauCommitVaRollbackThiKhongEvict() {
        UUID t = UUID.randomUUID();
        MenuItem seed = TenantContext.callAs(t, () -> inTx(() -> createMenu.execute(t,
                new CreateMenuUseCase.Command("base", new MenuDetails("B", "/base", null, null, 0, true, null)))));
        assertThat(seed).isNotNull();
        redisTemplate.opsForValue().set(cacheKey(t), "[]", Duration.ofMinutes(5));

        // Rollback: không evict
        TenantContext.runAs(t, () -> {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(st -> {
                createMenu.execute(t, new CreateMenuUseCase.Command("rolled",
                        new MenuDetails("R", "/rolled", null, null, 0, true, null)));
                st.setRollbackOnly();
            });
        });
        assertThat(redisTemplate.hasKey(cacheKey(t))).as("rollback thì không evict").isTrue();
        assertThat(TenantContext.callAs(t, () -> inTx(() -> repository.findAll(t)))).hasSize(1);

        // Commit: trong transaction (trước commit) khóa còn, sau commit mất
        TenantContext.runAs(t, () -> {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.executeWithoutResult(st -> {
                createMenu.execute(t, new CreateMenuUseCase.Command("kept",
                        new MenuDetails("K", "/kept", null, null, 0, true, null)));
                assertThat(redisTemplate.hasKey(cacheKey(t))).as("chưa commit thì chưa evict").isTrue();
            });
        });
        assertThat(redisTemplate.hasKey(cacheKey(t))).as("sau commit đã evict").isFalse();
    }

    // ---------- Redis chết (phải chạy cuối) ----------

    @Test
    @Order(100)
    void redisChetThiDocVaGhiVanThanhCongTuDbVaHealthVanUp() throws Exception {
        UUID t = UUID.randomUUID();
        create(t, "before", "/before", null);
        mvc.perform(get("/internal/menus/me").with(user(t))).andExpect(jsonPath("$.length()").value(1));

        redis.getDockerClient().stopContainerCmd(redis.getContainerId()).exec();

        mvc.perform(get("/internal/menus/me").with(user(t)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        // Ghi vẫn thành công dù evict lỗi (chỉ log WARN)
        create(t, "after", "/after", null);
        mvc.perform(get("/internal/menus/me").with(user(t)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        // Health không phụ thuộc Redis
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
