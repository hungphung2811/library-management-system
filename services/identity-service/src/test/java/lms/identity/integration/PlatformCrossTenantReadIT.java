package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0008 cho identity trên PostgreSQL thật, chạy bằng vai trò identity_app (NOSUPERUSER NOBYPASSRLS): platform admin
 * (token không tenantId) đọc xuyên tenant ở GET danh sách; mọi người khác bỏ qua tham số tenantId; ghi bị chặn.
 * Các test "cong*" là cổng kiểm chứng Hibernate isRoot: phải đạt trước khi tin các test còn lại.
 * Cần Docker. Chạy: mvn -pl services/identity-service test -Dtest=PlatformCrossTenantReadIT
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
        "app.internal-api-key=test-internal-key"
})
@Testcontainers
class PlatformCrossTenantReadIT {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    static boolean seeded;
    static CrossTenantFixture fx;
    static UUID tenantB;
    static String adminA;
    static String adminB;
    static String platform;
    static String platformWithTenant;
    static String plainA;
    static UUID onlyAUser;
    static UUID onlyBUser;
    static UUID sharedUser;
    static UUID noMembershipUser;

    @BeforeEach
    void seed() {
        if (seeded) return;
        seeded = true;
        fx = new CrossTenantFixture(rest, jdbc, postgres);
        adminA = fx.token("admin", "Admin@12345");
        tenantB = fx.newTenant("xt-b");
        adminB = fx.tenantBAdmin(tenantB, "xt-b", adminA);

        // phòng ban: DEPT-A (tenant A), DEPT-B (tenant B)
        assertThat(fx.call(HttpMethod.POST, "/internal/departments", adminA,
                Map.of("code", "DEPT-A", "name", "Phòng A"), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fx.call(HttpMethod.POST, "/internal/departments", adminB,
                Map.of("code", "DEPT-B", "name", "Phòng B"), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // role: ROLE_B_ONLY ở tenant B có permission (kiểm role_permissions hiện đủ qua EXISTS ở phạm vi ALL)
        fx.call(HttpMethod.GET, "/internal/permissions", adminA, null, List.class); // đăng ký khóa
        fx.call(HttpMethod.GET, "/internal/departments", adminA, null, List.class);
        fx.call(HttpMethod.GET, "/internal/roles", adminA, null, List.class);
        fx.call(HttpMethod.GET, "/internal/users", adminA, null, Map.class);
        ResponseEntity<Map> roleB = fx.call(HttpMethod.POST, "/internal/roles", adminB,
                Map.of("name", "ROLE_B_ONLY", "description", "Chỉ ở B", "isAdmin", false), Map.class);
        assertThat(roleB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fx.call(HttpMethod.POST, "/internal/roles/" + roleB.getBody().get("id") + "/permissions", adminB,
                Map.of("permissionCode", "DepartmentController/list"), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fx.call(HttpMethod.POST, "/internal/roles", adminA,
                Map.of("name", "ROLE_A_ONLY", "description", "Chỉ ở A", "isAdmin", false), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // người dùng: chỉ A, chỉ B, thuộc cả hai, không có membership nào
        onlyAUser = fx.createUserIn(adminA, "xt-only-a");
        onlyBUser = fx.createUserIn(adminB, "xt-only-b");
        sharedUser = fx.createUserIn(adminA, "xt-shared");
        jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, 'ACTIVE')", sharedUser, tenantB);
        noMembershipUser = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, full_name, email, status, created_at, updated_at) "
                + "VALUES (?, 'xt-orphan', 'Mồ côi', 'xt-orphan@x.vn', 'ACTIVE', now(), now())", noMembershipUser);

        platform = fx.token("platform-root", "PlatformRoot@123");
        platformWithTenant = fx.platformAdminWithTenant(adminA, "xt-padm-a", "DepartmentController/list",
                "AccessControlController/listRoles", "UserController/search");
        plainA = fx.plainUserOfA(adminA, "xt-plain-a", "DepartmentController/list", "AccessControlController/listRoles",
                "UserController/search");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String url, String token) {
        ResponseEntity<List> r = fx.call(HttpMethod.GET, url, token, null, List.class);
        assertThat(r.getStatusCode()).as(url).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static List<String> values(List<Map<String, Object>> items, String field) {
        return items.stream().map(i -> String.valueOf(i.get(field))).toList();
    }

    // ---- cổng kiểm chứng: Hibernate isRoot thật sự bỏ điều kiện @TenantId ----

    @Test
    void congDepartmentsKhongTenantIdThayCaHaiTenant() {
        List<Map<String, Object>> all = list("/internal/departments", platform);
        assertThat(values(all, "code")).contains("DEPT-A", "DEPT-B");
        assertThat(all).filteredOn(d -> "DEPT-A".equals(d.get("code"))).extracting(d -> d.get("tenantId"))
                .containsExactly(TENANT_A.toString());
        assertThat(all).filteredOn(d -> "DEPT-B".equals(d.get("code"))).extracting(d -> d.get("tenantId"))
                .containsExactly(tenantB.toString());
    }

    @Test
    void congRolesKhongTenantIdThayCaHaiTenantVaPermissionCodesDay() {
        List<Map<String, Object>> all = list("/internal/roles", platform);
        assertThat(values(all, "name")).contains("ROLE_A_ONLY", "ROLE_B_ONLY", "B_ADMIN");
        Map<String, Object> roleB = all.stream().filter(r -> "ROLE_B_ONLY".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(roleB.get("tenantId")).isEqualTo(tenantB.toString());
        assertThat((List<String>) roleB.get("permissions")).containsExactly("DepartmentController/list");
        Map<String, Object> roleA = all.stream().filter(r -> "ROLE_A_ONLY".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(roleA.get("tenantId")).isEqualTo(TENANT_A.toString());
    }

    @Test
    void congMotTenantChiThayTenantDo() {
        List<Map<String, Object>> deptA = list("/internal/departments?tenantId=" + TENANT_A, platform);
        assertThat(values(deptA, "code")).contains("DEPT-A").doesNotContain("DEPT-B");
        assertThat(values(deptA, "tenantId")).containsOnly(TENANT_A.toString());
        List<Map<String, Object>> rolesB = list("/internal/roles?tenantId=" + tenantB, platform);
        assertThat(values(rolesB, "name")).contains("ROLE_B_ONLY").doesNotContain("ROLE_A_ONLY");
        assertThat(values(rolesB, "tenantId")).containsOnly(tenantB.toString());
    }

    @Test
    void congTenantIdKhongTonTaiTraRong200() {
        String ghost = UUID.randomUUID().toString();
        assertThat(list("/internal/departments?tenantId=" + ghost, platform)).isEmpty();
        assertThat(list("/internal/roles?tenantId=" + ghost, platform)).isEmpty();
        assertThat(list("/internal/departments/tree?tenantId=" + ghost, platform)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void congSaiDinhDangTraBadRequestInvalidTenantId() {
        for (String url : List.of("/internal/departments?tenantId=khong-phai-uuid", "/internal/roles?tenantId=123",
                "/internal/users?tenantId=x", "/internal/departments/tree?tenantId=x",
                "/internal/permissions?tenantId=x")) {
            ResponseEntity<Map> r = fx.call(HttpMethod.GET, url, platform, null, Map.class);
            assertThat(r.getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code")).as(url).isEqualTo("INVALID_TENANT_ID");
        }
    }

    // ---- cây phòng ban ----

    @Test
    @SuppressWarnings("unchecked")
    void cayPhongBanOAllLaRungMoiNutCoTenantId() {
        // phòng ban con trong mỗi tenant
        String parentA = (String) list("/internal/departments?tenantId=" + TENANT_A, platform).stream()
                .filter(d -> "DEPT-A".equals(d.get("code"))).findFirst().orElseThrow().get("id");
        assertThat(fx.call(HttpMethod.POST, "/internal/departments", adminA,
                Map.of("code", "DEPT-A-CHILD", "name", "Con A", "parentId", parentA), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> forest = list("/internal/departments/tree", platform);
        List<Map<String, Object>> flat = new ArrayList<>();
        flatten(forest, flat);
        assertThat(flat).allSatisfy(n -> assertThat(n.get("tenantId")).isNotNull());
        Map<String, Object> rootA = forest.stream().filter(n -> "DEPT-A".equals(n.get("code"))).findFirst().orElseThrow();
        assertThat(((List<Map<String, Object>>) rootA.get("children"))).extracting(c -> c.get("code"))
                .containsExactly("DEPT-A-CHILD");
        assertThat(values(forest, "code")).contains("DEPT-A", "DEPT-B");
    }

    @SuppressWarnings("unchecked")
    private static void flatten(List<Map<String, Object>> nodes, List<Map<String, Object>> out) {
        for (Map<String, Object> n : nodes) {
            out.add(n);
            flatten((List<Map<String, Object>>) n.get("children"), out);
        }
    }

    // ---- người dùng (bảng toàn cục) ----

    @Test
    @SuppressWarnings("unchecked")
    void usersOAllLaCapUserTenantTotalDungVaNguoiKhongMembershipKhongXuatHien() {
        ResponseEntity<Map> r = fx.call(HttpMethod.GET, "/internal/users?size=100", platform, null, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.getBody().get("items");
        long memberships = fx.countSuperuser("SELECT count(*) FROM tenant_memberships m JOIN users u ON u.id = m.user_id");
        assertThat(((Number) r.getBody().get("total")).longValue()).isEqualTo(memberships);
        assertThat(items).hasSize((int) memberships);

        List<String> sharedRows = items.stream().filter(i -> sharedUser.toString().equals(i.get("id")))
                .map(i -> String.valueOf(i.get("tenantId"))).toList();
        assertThat(sharedRows).containsExactlyInAnyOrder(TENANT_A.toString(), tenantB.toString());
        assertThat(items).filteredOn(i -> onlyAUser.toString().equals(i.get("id"))).extracting(i -> i.get("tenantId"))
                .containsExactly(TENANT_A.toString());
        assertThat(items).filteredOn(i -> onlyBUser.toString().equals(i.get("id"))).extracting(i -> i.get("tenantId"))
                .containsExactly(tenantB.toString());
        assertThat(values(items, "id")).doesNotContain(noMembershipUser.toString());
        assertThat(items).allSatisfy(i -> assertThat(i.get("tenantId")).isNotNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usersOAllPhanTrangTheoCapVaLocTuKhoa() {
        // keyword "xt-" khớp 5 người dùng seed (only-a, only-b, shared, b-admin, plain, padm...), tính theo cặp
        long expected = fx.countSuperuser("SELECT count(*) FROM tenant_memberships m JOIN users u ON u.id = m.user_id "
                + "WHERE lower(u.username) LIKE 'xt-%'");
        ResponseEntity<Map> p0 = fx.call(HttpMethod.GET, "/internal/users?keyword=xt-&size=2&page=0", platform, null, Map.class);
        ResponseEntity<Map> p1 = fx.call(HttpMethod.GET, "/internal/users?keyword=xt-&size=2&page=1", platform, null, Map.class);
        assertThat(((Number) p0.getBody().get("total")).longValue()).isEqualTo(expected);
        assertThat(((List<?>) p0.getBody().get("items"))).hasSize(2);
        assertThat(((List<?>) p1.getBody().get("items"))).hasSize((int) Math.min(2, expected - 2));
        List<String> keys = new ArrayList<>();
        for (ResponseEntity<Map> p : List.of(p0, p1)) {
            for (Map<String, Object> i : (List<Map<String, Object>>) p.getBody().get("items")) {
                keys.add(i.get("id") + "|" + i.get("tenantId"));
            }
        }
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    @SuppressWarnings("unchecked")
    void usersMotTenantGiuNguyenHanhViVaCoTenantId() {
        ResponseEntity<Map> r = fx.call(HttpMethod.GET, "/internal/users?size=100&tenantId=" + tenantB, platform, null, Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.getBody().get("items");
        assertThat(values(items, "tenantId")).containsOnly(tenantB.toString());
        assertThat(values(items, "id")).contains(onlyBUser.toString(), sharedUser.toString())
                .doesNotContain(onlyAUser.toString(), noMembershipUser.toString());
        long inB = fx.countSuperuser("SELECT count(*) FROM tenant_memberships WHERE tenant_id = ?", tenantB);
        assertThat(((Number) r.getBody().get("total")).longValue()).isEqualTo(inB);

        ResponseEntity<Map> ghost = fx.call(HttpMethod.GET, "/internal/users?tenantId=" + UUID.randomUUID(), platform, null, Map.class);
        assertThat(ghost.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>) ghost.getBody().get("items"))).isEmpty();
        assertThat(((Number) ghost.getBody().get("total")).intValue()).isZero();
    }

    // ---- người gọi khác bỏ qua tenantId ----

    @Test
    @SuppressWarnings("unchecked")
    void nguoiKhacGuiTenantIdKhacVanChiThayTenantCuaToken() {
        for (String token : List.of(adminA, plainA, platformWithTenant)) {
            List<Map<String, Object>> depts = list("/internal/departments?tenantId=" + tenantB, token);
            assertThat(values(depts, "code")).contains("DEPT-A").doesNotContain("DEPT-B");
            assertThat(values(depts, "tenantId")).containsOnly(TENANT_A.toString());
            List<Map<String, Object>> roles = list("/internal/roles?tenantId=" + tenantB, token);
            assertThat(values(roles, "name")).doesNotContain("ROLE_B_ONLY", "B_ADMIN");
            assertThat(values(roles, "tenantId")).containsOnly(TENANT_A.toString());

            ResponseEntity<Map> users = fx.call(HttpMethod.GET, "/internal/users?size=100&tenantId=" + tenantB, token, null,
                    Map.class);
            assertThat(users.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> items = (List<Map<String, Object>>) users.getBody().get("items");
            assertThat(values(items, "tenantId")).containsOnly(TENANT_A.toString());
            assertThat(values(items, "id")).contains(onlyAUser.toString()).doesNotContain(onlyBUser.toString());

            // sai định dạng từ người khác: bị bỏ qua, không lỗi
            assertThat(fx.call(HttpMethod.GET, "/internal/departments?tenantId=khong-phai-uuid", token, null, List.class)
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ---- ghi và permissions ----

    @Test
    @SuppressWarnings("unchecked")
    void platformAdminKhongTenantBiTuChoiGhiVaKhongCoDongNaoDuocGhi() {
        long deptsBefore = fx.countSuperuser("SELECT count(*) FROM departments");
        long rolesBefore = fx.countSuperuser("SELECT count(*) FROM roles");
        ResponseEntity<Map> dept = fx.call(HttpMethod.POST, "/internal/departments", platform,
                Map.of("code", "XT-WRITE", "name", "Ghi"), Map.class);
        ResponseEntity<Map> role = fx.call(HttpMethod.POST, "/internal/roles", platform,
                Map.of("name", "XT_WRITE_ROLE", "description", "Ghi", "isAdmin", false), Map.class);
        assertThat(dept.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dept.getBody().get("code")).isEqualTo("TENANT_REQUIRED");
        assertThat(role.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(role.getBody().get("code")).isEqualTo("TENANT_REQUIRED");
        assertThat(fx.countSuperuser("SELECT count(*) FROM departments")).isEqualTo(deptsBefore);
        assertThat(fx.countSuperuser("SELECT count(*) FROM roles")).isEqualTo(rolesBefore);
        assertThat(fx.countSuperuser("SELECT count(*) FROM departments WHERE code = 'XT-WRITE'")).isZero();
        assertThat(fx.countSuperuser("SELECT count(*) FROM roles WHERE name = 'XT_WRITE_ROLE'")).isZero();
        // GET theo id không gắn annotation: vẫn TENANT_REQUIRED
        String deptId = (String) list("/internal/departments?tenantId=" + TENANT_A, platform).get(0).get("id");
        assertThat(fx.call(HttpMethod.GET, "/internal/departments/" + deptId, platform, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void permissionsVoiPlatformAdminKhongTenantTra200() {
        List<Map<String, Object>> permissions = list("/internal/permissions", platform);
        assertThat(values(permissions, "code")).contains("DepartmentController/list");
        // dữ liệu toàn cục: tenantId (nếu có) bị bỏ qua, cùng kết quả
        assertThat(list("/internal/permissions?tenantId=" + tenantB, platform)).hasSameSizeAs(permissions);
    }
}
