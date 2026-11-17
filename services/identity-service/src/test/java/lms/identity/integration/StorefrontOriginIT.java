package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0013 trên PostgreSQL thật, chạy bằng vai trò identity_app: API platform origin storefront (thêm/bật tắt/xóa),
 * UNIQUE toàn hệ thống kể cả đua ghi, giới hạn 10 đồng thời, lịch sử chỉ thêm (trigger + REVOKE), allowedOrigins chỉ
 * origin đang bật trong cấp token storefront, tenant admin chỉ xem origin của mình, refresh storefront sai tenant 401 và
 * không xoay vòng. Cần Docker. Chạy: mvn -pl services/identity-service -am test -Dtest=StorefrontOriginIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/identity",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.admin.properties.request.timeout.ms=500",
        "spring.kafka.admin.properties.default.api.timeout.ms=500",
        "auth.bootstrap-admin.password=Admin@12345",
        "auth.bootstrap.platform-admin-username=platform-root",
        "auth.bootstrap.platform-admin-password=PlatformRoot@123",
        "app.internal-api-key=test-internal-key",
        "lms.identity.storefront.client-secret=it-storefront-secret"
})
@Testcontainers
class StorefrontOriginIT {

    static final String SECRET = "it-storefront-secret";
    static final String ADMIN_PASSWORD = "TempAdmin@123";
    static final String ADMIN_NEW_PASSWORD = "NewAdmin@12345";
    static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;

    // ---- tiện ích ----

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> call(HttpMethod method, String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("rawtypes")
    private String platformToken() {
        ResponseEntity<Map> r = call(HttpMethod.POST, "/internal/auth/login", null,
                Map.of("username", "platform-root", "password", "PlatformRoot@123"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    @SuppressWarnings("rawtypes")
    private UUID createTenant(String code, List<String> modules) {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Cửa hàng " + code, "newAdmin",
                Map.of("username", code + "-admin", "fullName", "Quản trị " + code, "email", code + "@x.vn",
                        "temporaryPassword", ADMIN_PASSWORD)));
        body.put("modules", modules);
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/platform/tenants", platformToken(), body);
        assertThat(created.getStatusCode()).as("tạo tenant %s", code).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private static String base(UUID tenantId) {
        return "/api/platform/tenants/" + tenantId + "/storefront-origins";
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> addOrigin(String token, UUID tenantId, String origin) {
        return call(HttpMethod.POST, base(tenantId), token, Map.of("origin", origin));
    }

    @SuppressWarnings("rawtypes")
    private String addOk(String token, UUID tenantId, String origin) {
        ResponseEntity<Map> r = addOrigin(token, tenantId, origin);
        assertThat(r.getStatusCode()).as("thêm %s", origin).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<String> accessTokens(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Storefront-Client-Secret", SECRET);
        return rest.exchange("/internal/storefront/access-tokens", HttpMethod.POST,
                new HttpEntity<>("{\"tenantCode\":\"" + code + "\"}", headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> allowedOrigins(String code) throws Exception {
        ResponseEntity<String> r = accessTokens(code);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = JSON.readValue(r.getBody(), Map.class);
        assertThat(body.keySet()).containsExactlyInAnyOrder("tenantId", "code", "name", "accessToken", "expiresIn",
                "allowedOrigins");
        return (List<String>) body.get("allowedOrigins");
    }

    private <T> T query(String sql, Class<T> type, Object... args) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, type) : null;
            }
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), AppRoleDatabase.APP_ROLE, AppRoleDatabase.APP_ROLE);
    }

    // ---- CRUD, lịch sử, allowedOrigins ----

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void themBatTatXoaGhiLichSuVaAllowedOriginsChiGomOriginDangBat() throws Exception {
        UUID tenant = createTenant("or-crud", List.of("SALES"));
        String op = platformToken();
        assertThat(allowedOrigins("or-crud")).isEmpty();

        ResponseEntity<Map> created = addOrigin(op, tenant, "HTTPS://Shop.Or-Crud.vn:443/");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("origin", "https://shop.or-crud.vn").containsEntry("enabled", true);
        String off = addOk(op, tenant, "https://old.or-crud.vn");
        String originId = (String) created.getBody().get("id");

        assertThat(call(HttpMethod.PUT, base(tenant) + "/" + off, op, Map.of("enabled", false)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(allowedOrigins("or-crud")).containsExactly("https://shop.or-crud.vn");

        ResponseEntity<Map> list = call(HttpMethod.GET, base(tenant), op, null);
        assertThat(list.getBody()).containsEntry("maxOrigins", 10);
        assertThat((List<Map>) list.getBody().get("origins")).extracting(m -> m.get("origin"))
                .containsExactly("https://shop.or-crud.vn", "https://old.or-crud.vn");

        // PUT cùng giá trị là no-op: không thêm dòng lịch sử
        long before = query("SELECT count(*) FROM tenant_storefront_origin_history WHERE tenant_id = ?::uuid", Long.class, tenant.toString());
        call(HttpMethod.PUT, base(tenant) + "/" + off, op, Map.of("enabled", false));
        assertThat(query("SELECT count(*) FROM tenant_storefront_origin_history WHERE tenant_id = ?::uuid", Long.class, tenant.toString()))
                .isEqualTo(before);

        assertThat(call(HttpMethod.DELETE, base(tenant) + "/" + originId, op, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(allowedOrigins("or-crud")).isEmpty();

        // mỗi thay đổi đúng một dòng lịch sử: ADDED x2, DISABLED, REMOVED (giữ chuỗi origin của dòng đã xóa)
        List<String> actions = new ArrayList<>();
        try (Connection c = AppRoleDatabase.superuser(postgres); PreparedStatement ps = c.prepareStatement(
                "SELECT action || ':' || origin FROM tenant_storefront_origin_history WHERE tenant_id = ?::uuid "
                        + "ORDER BY occurred_at, action")) {
            ps.setString(1, tenant.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) actions.add(rs.getString(1));
            }
        }
        assertThat(actions).containsExactlyInAnyOrder("ADDED:https://shop.or-crud.vn", "ADDED:https://old.or-crud.vn",
                "DISABLED:https://old.or-crud.vn", "REMOVED:https://shop.or-crud.vn");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void lichSuChiThemTriggerVaRevokeChanSuaXoaKeCaVaiTroUngDung() throws Exception {
        UUID tenant = createTenant("or-hist", List.of("SALES"));
        addOk(platformToken(), tenant, "https://hist.vn");

        assertThatThrownBy(() -> query("WITH u AS (UPDATE tenant_storefront_origin_history SET origin = 'x' RETURNING 1) SELECT count(*) FROM u", Long.class))
                .hasMessageContaining("chỉ thêm");
        assertThatThrownBy(() -> query("WITH d AS (DELETE FROM tenant_storefront_origin_history RETURNING 1) SELECT count(*) FROM d", Long.class))
                .hasMessageContaining("chỉ thêm");
        assertThat(query("SELECT has_table_privilege('identity_app', 'tenant_storefront_origin_history', 'UPDATE')", Boolean.class)).isFalse();
        assertThat(query("SELECT has_table_privilege('identity_app', 'tenant_storefront_origin_history', 'DELETE')", Boolean.class)).isFalse();
        assertThat(query("SELECT has_table_privilege('identity_app', 'tenant_storefront_origin_history', 'INSERT')", Boolean.class)).isTrue();
        assertThat(query("SELECT has_table_privilege('identity_app', 'tenant_storefront_origins', 'DELETE')", Boolean.class)).isTrue();
        try (Connection c = appConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE tenant_storefront_origin_history SET origin = 'x'")) {
            assertThatThrownBy(ps::executeUpdate).hasMessageContaining("permission denied");
        }
    }

    // ---- UNIQUE toàn hệ thống, giới hạn, quy tắc ----

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void originDuyNhatToanHeThongKemTenantChuVaLoiChuanHoa() throws Exception {
        UUID a = createTenant("or-uni-a", List.of("SALES"));
        UUID b = createTenant("or-uni-b", List.of("SALES"));
        String op = platformToken();
        addOk(op, a, "https://shared.vn");

        ResponseEntity<Map> dup = addOrigin(op, b, "HTTPS://SHARED.vn:443");
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).containsEntry("code", "ORIGIN_DUPLICATED");
        assertThat((List<String>) dup.getBody().get("details")).containsExactly("tenantId=" + a);
        // cùng tenant cũng 409
        assertThat(addOrigin(op, a, "https://shared.vn/").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // origin tắt vẫn giữ chỗ
        // (gồm dòng đang tắt: bật rồi tắt thông qua PUT)
        String id = addOk(op, a, "https://held.vn");
        call(HttpMethod.PUT, base(a) + "/" + id, op, Map.of("enabled", false));
        assertThat(addOrigin(op, b, "https://held.vn").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> bad = addOrigin(op, b, "https://shop.vn/path");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bad.getBody()).containsEntry("code", "ORIGIN_INVALID");
        assertThat((List<String>) bad.getBody().get("details")).containsExactly("PATH");
        ResponseEntity<Map> http = addOrigin(op, b, "http://shop.vn");
        assertThat(http.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((List<String>) http.getBody().get("details")).containsExactly("HTTP_NOT_ALLOWED");
        assertThat(addOrigin(op, b, "http://localhost:5173").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(call(HttpMethod.POST, base(b), op, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // chuyển domain: xóa rồi thêm cho tenant khác
        ResponseEntity<Map> list = call(HttpMethod.GET, base(a), op, null);
        String sharedId = (String) ((List<Map>) list.getBody().get("origins")).get(0).get("id");
        call(HttpMethod.DELETE, base(a) + "/" + sharedId, op, null);
        assertThat(addOrigin(op, b, "https://shared.vn").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void thuMuoiMotBi409VaHaiYeuCauDongThoiOChinGiuaChiThanhCongMot() throws Exception {
        UUID t = createTenant("or-limit", List.of("SALES"));
        String op = platformToken();
        for (int i = 0; i < 9; i++) addOk(op, t, "https://l" + i + ".vn");

        List<ResponseEntity<Map>> results = parallel(
                () -> addOrigin(op, t, "https://last-a.vn"), () -> addOrigin(op, t, "https://last-b.vn"));

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 409).findFirst().get().getBody())
                .containsEntry("code", "ORIGIN_LIMIT_EXCEEDED");
        assertThat(query("SELECT count(*) FROM tenant_storefront_origins WHERE tenant_id = ?::uuid", Long.class, t.toString()))
                .isEqualTo(10L);
        assertThat(addOrigin(op, t, "https://l-eleven.vn").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void haiTenantThemCungMotOriginDongThoiDungMotThangMotLa409() throws Exception {
        UUID a = createTenant("or-race-a", List.of("SALES"));
        UUID b = createTenant("or-race-b", List.of("SALES"));
        String op = platformToken();

        List<ResponseEntity<Map>> results = parallel(
                () -> addOrigin(op, a, "https://race.vn"), () -> addOrigin(op, b, "https://race.vn"));

        assertThat(results).extracting(r -> r.getStatusCode().value()).containsExactlyInAnyOrder(201, 409);
        assertThat(results.stream().filter(r -> r.getStatusCode().value() == 409).findFirst().get().getBody())
                .containsEntry("code", "ORIGIN_DUPLICATED");
        assertThat(query("SELECT count(*) FROM tenant_storefront_origins WHERE origin = 'https://race.vn'", Long.class))
                .isEqualTo(1L);
    }

    @SafeVarargs
    private static <T> List<T> parallel(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("rawtypes")
    void originCuaTenantKhacQuaDuongDanTenantNayLa404VaKhongDoi() throws Exception {
        UUID a = createTenant("or-iso-a", List.of("SALES"));
        UUID b = createTenant("or-iso-b", List.of("SALES"));
        String op = platformToken();
        String ofB = addOk(op, b, "https://iso-b.vn");

        ResponseEntity<Map> put = call(HttpMethod.PUT, base(a) + "/" + ofB, op, Map.of("enabled", false));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put.getBody()).containsEntry("code", "STOREFRONT_ORIGIN_NOT_FOUND");
        assertThat(call(HttpMethod.DELETE, base(a) + "/" + ofB, op, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(query("SELECT enabled FROM tenant_storefront_origins WHERE id = ?::uuid", Boolean.class, ofB)).isTrue();
        // tenant không tồn tại
        assertThat(call(HttpMethod.GET, base(UUID.randomUUID()), op, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- cấp token storefront ----

    @Test
    void tenantKhongKhaDungVanMotKetQuaDuCoOrigin() throws Exception {
        UUID lending = createTenant("or-lend", List.of("LENDING"));
        // tenant chỉ LENDING vẫn có thể được khai báo origin trước khi bật SALES; storefront vẫn 404 đồng nhất
        assertThat(addOrigin(platformToken(), lending, "https://lend.vn").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> r = accessTokens("or-lend");
        ResponseEntity<String> missing = accessTokens("or-khong-co");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().replaceAll("\"timestamp\":\"[^\"]*\"", "")).isEqualTo(
                missing.getBody().replaceAll("\"timestamp\":\"[^\"]*\"", ""));
        assertThat(r.getBody()).doesNotContain("lend.vn");
    }

    // ---- phân quyền: tenant admin chỉ xem ----

    @SuppressWarnings("rawtypes")
    private String tenantAdminToken(String code) {
        String user = code + "-admin";
        ResponseEntity<Map> first = call(HttpMethod.POST, "/internal/auth/login", null,
                Map.of("username", user, "password", ADMIN_PASSWORD, "tenantCode", code));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> changed = call(HttpMethod.POST, "/internal/auth/password",
                (String) first.getBody().get("accessToken"),
                Map.of("currentPassword", ADMIN_PASSWORD, "newPassword", ADMIN_NEW_PASSWORD));
        assertThat(changed.getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<Map> second = call(HttpMethod.POST, "/internal/auth/login", null,
                Map.of("username", user, "password", ADMIN_NEW_PASSWORD, "tenantCode", code));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) second.getBody().get("accessToken");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tenantAdminChiXemOriginCuaMinhKhongLoNguoiTaoVaKhongGhiDuoc() throws Exception {
        UUID a = createTenant("or-adm-a", List.of("SALES"));
        UUID b = createTenant("or-adm-b", List.of("SALES"));
        String op = platformToken();
        addOk(op, a, "https://a1.vn");
        String a2 = addOk(op, a, "https://a2.vn");
        call(HttpMethod.PUT, base(a) + "/" + a2, op, Map.of("enabled", false));
        addOk(op, b, "https://b1.vn");
        String adminA = tenantAdminToken("or-adm-a");

        ResponseEntity<Map> mine = call(HttpMethod.GET, "/internal/storefront-origins", adminA, null);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map> origins = (List<Map>) mine.getBody().get("origins");
        assertThat(origins).extracting(m -> m.get("origin")).containsExactly("https://a1.vn", "https://a2.vn");
        assertThat(origins).extracting(m -> m.get("enabled")).containsExactly(true, false);
        assertThat(JSON.writeValueAsString(mine.getBody())).doesNotContain("createdBy", "updatedBy", "b1.vn");

        // không gọi được API platform, không có đường ghi
        assertThat(call(HttpMethod.GET, base(a), adminA, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, base(a), adminA, Map.of("origin", "https://hack.vn")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/internal/storefront-origins", adminA, Map.of("origin", "https://hack.vn"))
                .getStatusCode().is4xxClientError()).isTrue();
        // platform admin không có tenantId: 403 TENANT_REQUIRED; không token: 401
        assertThat(call(HttpMethod.GET, "/internal/storefront-origins", op, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/internal/storefront-origins", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- refresh storefront có tenantId ----

    @Test
    @SuppressWarnings({"rawtypes"})
    void refreshStorefrontSaiTenant401KhongXoayVongDungTenantThanhCong() throws Exception {
        UUID a = createTenant("or-rf-a", List.of("SALES"));
        UUID b = createTenant("or-rf-b", List.of("SALES"));
        for (String code : List.of("or-rf-a", "or-rf-b")) {
            ResponseEntity<Map> signup = call(HttpMethod.POST, "/internal/auth/signup", null, Map.of("tenantCode", code,
                    "username", "khach-" + code, "password", "Secret@12345", "fullName", "Khách " + code,
                    "email", "khach-" + code + "@khach.vn"));
            assertThat(signup.getStatusCode()).as("signup %s", code).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<Map> loginA = call(HttpMethod.POST, "/internal/auth/login", null, Map.of("username", "khach-or-rf-a",
                "password", "Secret@12345", "tenantCode", "or-rf-a", "channel", "STOREFRONT"));
        assertThat(loginA.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshA = (String) loginA.getBody().get("refreshToken");

        // gọi nhầm tenant B -> 401, token của A không bị thu hồi, không có phiên mới
        long tokensBefore = query("SELECT count(*) FROM refresh_tokens WHERE tenant_id = ?::uuid", Long.class, a.toString());
        ResponseEntity<Map> wrong = call(HttpMethod.POST, "/internal/auth/refresh", null,
                Map.of("refreshToken", refreshA, "channel", "STOREFRONT", "tenantId", b.toString()));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(query("SELECT count(*) FROM refresh_tokens WHERE tenant_id = ?::uuid", Long.class, a.toString()))
                .as("không phát phiên mới").isEqualTo(tokensBefore);
        assertThat(query("SELECT count(*) FROM refresh_tokens WHERE revoked AND tenant_id = ?::uuid", Long.class, a.toString()))
                .as("không token nào của tenant A bị thu hồi").isZero();

        // đúng tenant A -> 200, xoay vòng bình thường, phiên mới vẫn của A
        ResponseEntity<Map> right = call(HttpMethod.POST, "/internal/auth/refresh", null,
                Map.of("refreshToken", refreshA, "channel", "STOREFRONT", "tenantId", a.toString()));
        assertThat(right.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(right.getBody()).containsEntry("tenantId", a.toString());
        assertThat(query("SELECT count(*) FROM refresh_tokens WHERE revoked AND tenant_id = ?::uuid", Long.class, a.toString()))
                .as("token cũ bị xoay vòng đúng một lần").isEqualTo(1L);

        // đường cũ không gửi tenantId vẫn chạy (tương thích)
        ResponseEntity<Map> legacy = call(HttpMethod.POST, "/internal/auth/refresh", null,
                Map.of("refreshToken", right.getBody().get("refreshToken"), "channel", "STOREFRONT"));
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
