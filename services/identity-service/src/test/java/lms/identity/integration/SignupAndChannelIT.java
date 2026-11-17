package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Migration V18 + tự đăng ký thật + kênh token, dưới vai trò identity_app (chịu RLS): cờ signup_default (CHECK, chỉ mục
 * duy nhất), POST /internal/auth/signup (201, role signup_default, mass assignment bị bỏ qua, một mã chung khi không
 * khả dụng), login/refresh kênh STOREFRONT (aud, không admin, refresh giữ kênh, không chéo kênh), khóa getAny của role
 * thủ thư. Cần Docker. Chạy: mvn -o -pl services/identity-service -am test -Dtest=SignupAndChannelIT
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
class SignupAndChannelIT {

    static final String ADMIN_PASSWORD = "TempAdmin@123";
    static final String ADMIN_NEW_PASSWORD = "AdminNew@12345";
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
    private ResponseEntity<Map> post(String url, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("rawtypes")
    private String platformToken() {
        ResponseEntity<Map> r = post("/internal/auth/login", null,
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
        ResponseEntity<Map> created = post("/api/platform/tenants", platformToken(), body);
        assertThat(created.getStatusCode()).as("tạo tenant %s", code).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private static JsonNode claims(String jwt) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private void sql(String sql, Object... args) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.execute();
        }
    }

    private <T> T query(String sql, Class<T> type, Object... args) throws SQLException {
        try (Connection c = AppRoleDatabase.superuser(postgres); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, type) : null;
            }
        }
    }

    private static Map<String, Object> signupBody(String tenantCode, String username) {
        return new HashMap<>(Map.of("tenantCode", tenantCode, "username", username, "password", "Secret@12345",
                "fullName", "Khách " + username, "email", username + "@khach.vn"));
    }

    /**
     * Đặt role làm role đăng ký mặc định của tenant. Từ V20 tenant có SALES đã nhận role CUSTOMER mang cờ (mẫu Khách
     * hàng), nên gỡ cờ hiện có trước (chỉ mục duy nhất từng phần) để các ca dưới đây dùng MEMBER như trước.
     */
    private void markSignupRole(UUID tenantId, String roleName) throws SQLException {
        sql("UPDATE roles SET signup_default = FALSE WHERE tenant_id = ? AND signup_default", tenantId);
        sql("UPDATE roles SET signup_default = TRUE WHERE tenant_id = ? AND name = ?", tenantId, roleName);
    }

    // ---- migration V18 ----

    @Test
    void migrationV18TaoCotKenhCoRoleVaKhoaGetAnyChoMauThuThu() throws Exception {
        assertThat(query("SELECT column_default FROM information_schema.columns WHERE table_name = 'refresh_tokens' "
                + "AND column_name = 'channel'", String.class)).contains("STAFF");
        assertThat(query("SELECT count(*) FROM information_schema.columns WHERE table_name IN ('roles', "
                + "'role_templates') AND column_name = 'signup_default'", Long.class)).isEqualTo(2L);
        assertThat(query("SELECT count(*) FROM permissions WHERE code = 'BorrowingController/getAny' "
                + "AND service = 'circulation-service'", Long.class)).isEqualTo(1L);
        // chỉ thủ thư (a3) và quản trị (a1, a2) trong các mẫu LENDING nhận khóa; thành viên/độc giả/kiểm toán thì không
        List<String> templates = List.of("LIBRARIAN", "LIBRARY_ADMIN", "SUPER_ADMIN");
        for (String t : templates) {
            assertThat(query("SELECT count(*) FROM role_template_permissions p JOIN role_templates t ON t.id = "
                    + "p.template_id WHERE t.name = ? AND p.permission_code = 'BorrowingController/getAny'",
                    Long.class, t)).as(t).isEqualTo(1L);
        }
        assertThat(query("SELECT count(*) FROM role_template_permissions p JOIN role_templates t ON t.id = "
                + "p.template_id WHERE t.name IN ('MEMBER', 'DEPARTMENT_MANAGER') AND p.permission_code = "
                + "'BorrowingController/getAny'", Long.class)).isZero();
    }

    @Test
    void checkVaChiMucDuyNhatCuaCoSignupDefault() throws Exception {
        UUID tenantId = createTenant("it-flag", List.of("LENDING", "SALES"));
        // role admin không bao giờ mang cờ (CHECK)
        assertThatThrownBy(() -> sql("UPDATE roles SET signup_default = TRUE WHERE tenant_id = ? AND is_admin", tenantId))
                .hasMessageContaining("ck_roles_signup_not_admin");
        markSignupRole(tenantId, "MEMBER");
        // tối đa một role signup_default mỗi tenant (chỉ mục duy nhất từng phần)
        assertThatThrownBy(() -> sql("UPDATE roles SET signup_default = TRUE WHERE tenant_id = ? AND name = ?",
                tenantId, "LIBRARIAN"))
                .hasMessageContaining("uq_roles_signup_default_per_tenant");
        // mẫu admin cũng bị CHECK
        assertThatThrownBy(() -> sql("UPDATE role_templates SET signup_default = TRUE WHERE is_admin"))
                .hasMessageContaining("ck_role_templates_signup_not_admin");
    }

    @Test
    void tenantTaoMoiNhanRoleThuThuCoGetAny() throws Exception {
        UUID tenantId = createTenant("it-getany", List.of("LENDING"));
        assertThat(query("SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id WHERE "
                + "r.tenant_id = ? AND r.name = 'LIBRARIAN' AND rp.permission_code = 'BorrowingController/getAny'",
                Long.class, tenantId)).isEqualTo(1L);
        assertThat(query("SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id WHERE "
                + "r.tenant_id = ? AND r.name = 'MEMBER' AND rp.permission_code = 'BorrowingController/getAny'",
                Long.class, tenantId)).isZero();
    }

    // ---- tự đăng ký ----

    @Test
    @SuppressWarnings("rawtypes")
    void dangKyThatTaoTaiKhoanGanDungRoleBoQuaTruongLaVaTrungThiConflict() throws Exception {
        UUID tenantId = createTenant("it-signup", List.of("LENDING", "SALES"));
        markSignupRole(tenantId, "MEMBER");
        UUID adminRoleId = query("SELECT id FROM roles WHERE tenant_id = ? AND is_admin LIMIT 1", UUID.class, tenantId);

        Map<String, Object> body = signupBody("it-signup", "khach1");
        // mass assignment: mọi trường quyền lực đều bị bỏ qua
        body.put("roleId", adminRoleId.toString());
        body.put("roles", List.of("SUPER_ADMIN"));
        body.put("admin", true);
        body.put("isAdmin", true);
        body.put("platformAdmin", true);
        body.put("tenantId", UUID.randomUUID().toString());
        body.put("mustChangePassword", true);
        ResponseEntity<Map> created = post("/internal/auth/signup", null, body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID userId = query("SELECT user_id FROM credentials WHERE username = 'khach1'", UUID.class);
        assertThat(userId).isNotNull();
        assertThat(query("SELECT count(*) FROM users WHERE id = ?", Long.class, userId)).isEqualTo(1L);
        assertThat(query("SELECT count(*) FROM tenant_memberships WHERE user_id = ? AND tenant_id = ? AND status = "
                + "'ACTIVE'", Long.class, userId, tenantId)).isEqualTo(1L);
        assertThat(query("SELECT must_change_password FROM credentials WHERE user_id = ?", Boolean.class, userId))
                .isFalse();
        assertThat(query("SELECT platform_admin FROM credentials WHERE user_id = ?", Boolean.class, userId)).isFalse();
        // đúng MỘT role: role signup_default, không phải admin
        assertThat(query("SELECT count(*) FROM user_roles WHERE user_id = ?", Long.class, userId)).isEqualTo(1L);
        assertThat(query("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?",
                String.class, userId)).isEqualTo("MEMBER");
        assertThat(query("SELECT bool_or(r.is_admin) FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE "
                + "ur.user_id = ?", Boolean.class, userId)).isFalse();

        // trùng tên đăng nhập (kể cả khác hoa thường) và trùng email: một mã chung 409, không tạo thêm gì
        Map<String, Object> dupName = signupBody("it-signup", "KHACH1");
        dupName.put("email", "khac@khach.vn");
        ResponseEntity<Map> d1 = post("/internal/auth/signup", null, dupName);
        assertThat(d1.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> dupMail = signupBody("it-signup", "khach2");
        dupMail.put("email", "khach1@khach.vn");
        ResponseEntity<Map> d2 = post("/internal/auth/signup", null, dupMail);
        assertThat(d2.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(d2.getBody().get("code")).isEqualTo(d1.getBody().get("code"));
        assertThat(d2.getBody().get("message")).isEqualTo(d1.getBody().get("message"));
        assertThat(query("SELECT count(*) FROM credentials WHERE username IN ('khach2')", Long.class)).isZero();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void khongDangKyDuocMoiLyDoDeuCungMotPhanHoi403() throws Exception {
        UUID noSales = createTenant("it-nosales", List.of("LENDING"));
        UUID noFlag = createTenant("it-noflag", List.of("LENDING", "SALES"));
        UUID ok = createTenant("it-okflag", List.of("LENDING", "SALES"));
        sql("UPDATE roles SET signup_default = FALSE WHERE tenant_id = ?", noFlag); // V20: CUSTOMER mang cờ, gỡ để thử
        markSignupRole(noSales, "MEMBER");  // có cờ nhưng không bật SALES
        markSignupRole(ok, "MEMBER");
        sql("UPDATE tenants SET status = 'SUSPENDED' WHERE code = 'it-okflag'");

        List<ResponseEntity<Map>> responses = List.of(
                post("/internal/auth/signup", null, signupBody("it-nosales", "usr1")),     // không bật SALES
                post("/internal/auth/signup", null, signupBody("it-noflag", "usr2")),      // không có role signup_default
                post("/internal/auth/signup", null, signupBody("it-okflag", "usr3")),      // tenant bị ngưng
                post("/internal/auth/signup", null, signupBody("khong-ton-tai", "usr4")));  // không tồn tại
        for (ResponseEntity<Map> r : responses) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().get("code")).isEqualTo("SIGNUP_NOT_AVAILABLE");
            assertThat(r.getBody().get("message")).isEqualTo(responses.get(0).getBody().get("message"));
            assertThat(r.getBody().toString()).doesNotContain("it-no").doesNotContain("khong-ton-tai");
        }
        assertThat(query("SELECT count(*) FROM credentials WHERE username IN ('usr1','usr2','usr3','usr4')", Long.class))
                .isZero();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void dangKyHoSoSaiTra400VaKhongTaoGi() throws Exception {
        UUID tenantId = createTenant("it-badsignup", List.of("LENDING", "SALES"));
        markSignupRole(tenantId, "MEMBER");
        Map<String, Object> badName = signupBody("it-badsignup", "Có Dấu!");
        ResponseEntity<Map> r = post("/internal/auth/signup", null, badName);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(query("SELECT count(*) FROM users WHERE username = 'có dấu!'", Long.class)).isZero();
        assertThat(query("SELECT count(*) FROM credentials WHERE username LIKE 'c%d%'", Long.class)).isZero();
    }

    // ---- kênh token ----

    @Test
    @SuppressWarnings("rawtypes")
    void khachDangKyDangNhapStorefrontCoAudVaRefreshGiuKenhKhongCheoKenh() throws Exception {
        UUID tenantId = createTenant("it-channel", List.of("LENDING", "SALES"));
        markSignupRole(tenantId, "MEMBER");
        assertThat(post("/internal/auth/signup", null, signupBody("it-channel", "mua1")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // storefront: thiếu tenantCode -> 401
        ResponseEntity<Map> noTenant = post("/internal/auth/login", null,
                Map.of("username", "mua1", "password", "Secret@12345", "channel", "STOREFRONT"));
        assertThat(noTenant.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> login = post("/internal/auth/login", null, Map.of("username", "mua1", "password",
                "Secret@12345", "tenantCode", "it-channel", "channel", "STOREFRONT"));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String access = (String) login.getBody().get("accessToken");
        String refresh = (String) login.getBody().get("refreshToken");
        JsonNode c = claims(access);
        assertThat(c.get("aud").toString()).contains("lms-storefront");
        assertThat(c.has("admin")).isFalse();
        assertThat(c.has("platformAdmin")).isFalse();
        assertThat(c.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(query("SELECT channel FROM refresh_tokens ORDER BY created_at DESC LIMIT 1", String.class))
                .isEqualTo("STOREFRONT");

        // refresh qua kênh nhân viên (lms-api, không channel) -> 401, token không bị xoay vòng
        ResponseEntity<Map> crossStaff = post("/internal/auth/refresh", null, Map.of("refreshToken", refresh));
        assertThat(crossStaff.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // refresh đúng kênh -> token mới vẫn STOREFRONT
        ResponseEntity<Map> refreshed = post("/internal/auth/refresh", null,
                Map.of("refreshToken", refresh, "channel", "STOREFRONT"));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claims((String) refreshed.getBody().get("accessToken")).get("aud").toString())
                .contains("lms-storefront");
        assertThat(query("SELECT channel FROM refresh_tokens ORDER BY created_at DESC LIMIT 1", String.class))
                .isEqualTo("STOREFRONT");

        // kênh nhân viên: không aud (mặc định), refresh token nhân viên không dùng được ở kênh storefront
        ResponseEntity<Map> staff = post("/internal/auth/login", null,
                Map.of("username", "mua1", "password", "Secret@12345", "tenantCode", "it-channel"));
        assertThat(staff.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claims((String) staff.getBody().get("accessToken")).has("aud")).isFalse();
        ResponseEntity<Map> crossStore = post("/internal/auth/refresh", null,
                Map.of("refreshToken", staff.getBody().get("refreshToken"), "channel", "STOREFRONT"));
        assertThat(crossStore.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void adminTenantDangNhapStorefrontNhanTokenKhongAdmin() throws Exception {
        createTenant("it-adminsf", List.of("LENDING", "SALES"));
        // đổi mật khẩu tạm để token có đủ quyền admin ở kênh nhân viên
        ResponseEntity<Map> first = post("/internal/auth/login", null,
                Map.of("username", "it-adminsf-admin", "password", ADMIN_PASSWORD, "tenantCode", "it-adminsf"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/internal/auth/password", (String) first.getBody().get("accessToken"),
                Map.of("currentPassword", ADMIN_PASSWORD, "newPassword", ADMIN_NEW_PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> staff = post("/internal/auth/login", null,
                Map.of("username", "it-adminsf-admin", "password", ADMIN_NEW_PASSWORD, "tenantCode", "it-adminsf"));
        assertThat(claims((String) staff.getBody().get("accessToken")).path("admin").asBoolean()).isTrue();

        ResponseEntity<Map> storefront = post("/internal/auth/login", null, Map.of("username", "it-adminsf-admin",
                "password", ADMIN_NEW_PASSWORD, "tenantCode", "it-adminsf", "channel", "STOREFRONT"));
        assertThat(storefront.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode c = claims((String) storefront.getBody().get("accessToken"));
        assertThat(c.has("admin")).isFalse();
        assertThat(c.has("platformAdmin")).isFalse();
        assertThat(c.get("aud").toString()).contains("lms-storefront");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void platformAdminKhongCoPhienStorefrontCoQuyenNenTang() throws Exception {
        ResponseEntity<Map> r = post("/internal/auth/login", null, Map.of("username", "platform-root", "password",
                "PlatformRoot@123", "tenantCode", "default", "channel", "STOREFRONT"));
        // hoặc bị từ chối (không thuộc tenant), hoặc nhận token storefront đã bị thu hẹp: không bao giờ có admin/platformAdmin
        if (r.getStatusCode() == HttpStatus.OK) {
            JsonNode c = claims((String) r.getBody().get("accessToken"));
            assertThat(c.has("admin")).isFalse();
            assertThat(c.has("platformAdmin")).isFalse();
        } else {
            assertThat(r.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }
}
