package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lms.identity.contract.application.ProcessContractLifecycleUseCase;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
 * Hợp đồng thuê dịch vụ (ADR-0012, T3) trên PostgreSQL thật, ứng dụng chạy bằng vai trò identity_app (chịu RLS): API
 * platform, job vòng đời (gọi trực tiếp {@code runOnce(now)} với thời điểm giả lập), tự kích hoạt lại tenant đúng nguyên
 * nhân, thanh toán bất biến, nhắc hạn, tenant admin chỉ đọc và cách ly hai tenant. Job lập lịch tắt bằng
 * {@code lms.contract.job.enabled=false}. Cần Docker. Chạy: mvn -pl services/identity-service -am test
 * -Dtest=ContractLifecycleIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
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
        "lms.contract.job.enabled=false"
})
@Testcontainers
class ContractLifecycleIT {

    static final String DEFAULT_TENANT = "00000000-0000-0000-0000-0000000000d1";
    static final String ADMIN_PASSWORD = "TempAdmin@123";
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProcessContractLifecycleUseCase job;

    // ---------------------------------------------------------------- helpers

    private static LocalDate today() {
        return LocalDate.now(VN);
    }

    /** 12:00 giờ VN của ngày {@code d}: thời điểm giả lập cho {@code runOnce}. */
    private static Instant at(LocalDate d) {
        return d.atTime(12, 0).atZone(VN).toInstant();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("rawtypes")
    private String accessToken(String username, String password) {
        ResponseEntity<Map> response = rest.postForEntity("/internal/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(response.getStatusCode()).as("login %s", username).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("accessToken");
    }

    private String platformToken() {
        return accessToken("platform-root", "PlatformRoot@123");
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> loginTenant(String code) {
        Map<String, Object> body = new HashMap<>(Map.of("username", code + "-admin", "password", ADMIN_PASSWORD,
                "tenantCode", code));
        return rest.postForEntity("/internal/auth/login", body, Map.class);
    }

    /** Token quản trị viên tenant có claim admin (bỏ cờ phải đổi mật khẩu của tài khoản tạm). */
    private String tenantAdminToken(String code) {
        jdbc.update("UPDATE credentials SET must_change_password = false WHERE username = ?", code + "-admin");
        ResponseEntity<Map> login = loginTenant(code);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) login.getBody().get("accessToken");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private UUID createTenant(String code) {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Thư viện " + code, "newAdmin",
                Map.of("username", code + "-admin", "fullName", "Quản trị " + code, "email", code + "@x.vn",
                        "temporaryPassword", ADMIN_PASSWORD)));
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/platform/tenants", platformToken(), body, Map.class);
        assertThat(created.getStatusCode()).as("tạo tenant %s", code).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
    }

    private static Map<String, Object> contractBody(String name, List<String> modules, LocalDate start, LocalDate end) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("modules", modules);
        body.put("startDate", start.toString());
        if (end != null) body.put("endDate", end.toString());
        body.put("totalAmount", 1_000_000);
        body.put("internalNote", "ghi chú nội bộ " + name);
        return body;
    }

    @SuppressWarnings("rawtypes")
    private UUID draft(UUID tenant, String name, List<String> modules, LocalDate start, LocalDate end) {
        ResponseEntity<Map> created = call(HttpMethod.POST, "/api/platform/tenants/" + tenant + "/contracts",
                platformToken(), contractBody(name, modules, start, end), Map.class);
        assertThat(created.getStatusCode()).as("tạo hợp đồng %s: %s", name, created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "DRAFT");
        return UUID.fromString((String) created.getBody().get("id"));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> activate(UUID contract) {
        return call(HttpMethod.POST, "/api/platform/contracts/" + contract + "/activate", platformToken(), null, Map.class);
    }

    private UUID activeContract(UUID tenant, String name, List<String> modules, LocalDate start, LocalDate end) {
        UUID id = draft(tenant, name, modules, start, end);
        assertThat(activate(id).getStatusCode()).as("kích hoạt %s", name).isEqualTo(HttpStatus.OK);
        return id;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> platformPost(String path, Object body) {
        return call(HttpMethod.POST, path, platformToken(), body, Map.class);
    }

    private List<String> enabledModules(UUID tenant) {
        return jdbc.queryForList("SELECT module FROM tenant_modules WHERE tenant_id = ? AND enabled ORDER BY module",
                String.class, tenant);
    }

    /** "STATUS/CAUSE" (cause null thành "-"). */
    private String tenantState(UUID tenant) {
        return jdbc.queryForObject("SELECT status || '/' || COALESCE(suspension_cause, '-') FROM tenants WHERE id = ?",
                String.class, tenant);
    }

    private String contractStatus(UUID contract) {
        return jdbc.queryForObject("SELECT status FROM tenant_contracts WHERE id = ?", String.class, contract);
    }

    private int historyCount(UUID contract, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_contract_history WHERE contract_id = ? AND type = ?",
                Integer.class, contract, type);
    }

    private List<String> roleNames(UUID tenant) {
        return TenantContext.callAs(tenant, () -> jdbc.queryForList("SELECT name FROM roles ORDER BY name", String.class));
    }

    private ProcessContractLifecycleUseCase.Result run(Instant now) {
        ProcessContractLifecycleUseCase.Result result = job.runOnce(now);
        assertThat(result.failed()).as("không hợp đồng nào lỗi trong lượt").isZero();
        return result;
    }

    /** Tenant LENDING có hợp đồng C1 đã hết ân hạn: sau lượt job, tenant SUSPENDED do HẾT HẠN. */
    private record Suspended(UUID tenant, String code, UUID expired) {
    }

    private Suspended suspendedByExpiry(String code) {
        UUID tenant = createTenant(code);
        UUID c1 = activeContract(tenant, "C1", List.of("LENDING"), today().minusDays(10), today().minusDays(1));
        assertThat(tenantState(tenant)).isEqualTo("ACTIVE/-");
        run(at(today().plusDays(7)));
        assertThat(contractStatus(c1)).isEqualTo("EXPIRED");
        assertThat(tenantState(tenant)).isEqualTo("SUSPENDED/CONTRACT_EXPIRED");
        return new Suspended(tenant, code, c1);
    }

    // ---------------------------------------------------------------- backfill và tenant không hợp đồng

    @Test
    @SuppressWarnings("rawtypes")
    void backfillMoiTenantMotLegacyKhopModuleVaJobKhongTatGi() {
        UUID defaultTenant = UUID.fromString(DEFAULT_TENANT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_contracts WHERE tenant_id = ? AND legacy",
                Integer.class, defaultTenant)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT m.module FROM tenant_contract_modules m JOIN tenant_contracts c "
                + "ON c.id = m.contract_id WHERE c.tenant_id = ?", String.class, defaultTenant))
                .containsExactlyElementsOf(enabledModules(defaultTenant));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_contract_history WHERE tenant_id = ? "
                + "AND type = 'LEGACY_BACKFILLED' AND actor_type = 'SYSTEM' AND actor_id IS NULL", Integer.class,
                defaultTenant)).isEqualTo(1);

        run(at(today().plusYears(2)));
        assertThat(enabledModules(defaultTenant)).containsExactly("LENDING");
        assertThat(tenantState(defaultTenant)).isEqualTo("ACTIVE/-");
        assertThat(jdbc.queryForObject("SELECT status FROM tenant_contracts WHERE tenant_id = ? AND legacy",
                String.class, defaultTenant)).isEqualTo("ACTIVE");

        // tenant mới không có hợp đồng: job không động tới
        UUID fresh = createTenant("ct-fresh");
        run(at(today().plusYears(2)));
        assertThat(enabledModules(fresh)).containsExactly("LENDING");
        assertThat(tenantState(fresh)).isEqualTo("ACTIVE/-");
        ResponseEntity<Map> list = call(HttpMethod.GET, "/api/platform/tenants/" + fresh + "/contracts", platformToken(),
                null, Map.class);
        assertThat((List) list.getBody().get("contracts")).isEmpty();
        assertThat((List) list.getBody().get("modulesOutsideContract")).containsExactly("LENDING");
    }

    // ---------------------------------------------------------------- API: kiểm hợp lệ, version, chồng lấn

    @Test
    @SuppressWarnings("rawtypes")
    void kiemHopLeVersionVaChinhSuaTheoTrangThai() {
        UUID tenant = createTenant("ct-valid");
        String path = "/api/platform/tenants/" + tenant + "/contracts";
        String operator = platformToken();

        ResponseEntity<Map> noEnd = call(HttpMethod.POST, path, operator,
                contractBody("X", List.of("LENDING"), today(), null), Map.class);
        assertThat(noEnd.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noEnd.getBody().get("code")).isEqualTo("INVALID_CONTRACT");
        assertThat(call(HttpMethod.POST, path, operator, contractBody("X", List.of(), today(), today()), Map.class)
                .getBody().get("code")).isEqualTo("INVALID_MODULES");
        assertThat(call(HttpMethod.POST, path, operator, contractBody("X", List.of("PAYROLL"), today(), today()),
                Map.class).getBody().get("code")).isEqualTo("UNKNOWN_MODULE");
        assertThat(call(HttpMethod.POST, "/api/platform/tenants/" + UUID.randomUUID() + "/contracts", operator,
                contractBody("X", List.of("LENDING"), today(), today()), Map.class).getBody().get("code"))
                .isEqualTo("TENANT_NOT_FOUND");

        UUID id = draft(tenant, "Gói", List.of("LENDING"), today().plusDays(1), today().plusDays(30));
        ResponseEntity<Map> got = call(HttpMethod.GET, "/api/platform/contracts/" + id, operator, null, Map.class);
        assertThat(got.getBody()).containsEntry("legacy", false).containsEntry("totalAmount", 1_000_000)
                .containsEntry("outstandingAmount", 1_000_000).containsKey("payments");
        int version = (Integer) got.getBody().get("version");

        // sửa NHÁP đúng version; version cũ thì 409
        Map<String, Object> edit = contractBody("Gói sửa", List.of("LENDING", "SALES"), today().plusDays(1),
                today().plusDays(40));
        edit.put("version", version);
        ResponseEntity<Map> updated = call(HttpMethod.PUT, "/api/platform/contracts/" + id, operator, edit, Map.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).containsEntry("name", "Gói sửa");
        ResponseEntity<Map> stale = call(HttpMethod.PUT, "/api/platform/contracts/" + id, operator, edit, Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code")).isEqualTo("CONTRACT_VERSION_CONFLICT");

        // ACTIVE: chỉ ghi chú; đổi giá thì 409 CONTRACT_NOT_EDITABLE
        assertThat(activate(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        int v2 = (Integer) call(HttpMethod.GET, "/api/platform/contracts/" + id, operator, null, Map.class).getBody()
                .get("version");
        Map<String, Object> note = contractBody("Gói sửa", List.of("LENDING", "SALES"), today().plusDays(1),
                today().plusDays(40));
        note.put("internalNote", "chỉ đổi ghi chú");
        note.put("version", v2);
        assertThat(call(HttpMethod.PUT, "/api/platform/contracts/" + id, operator, note, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        int v3 = (Integer) call(HttpMethod.GET, "/api/platform/contracts/" + id, operator, null, Map.class).getBody()
                .get("version");
        Map<String, Object> price = new HashMap<>(note);
        price.put("totalAmount", 5);
        price.put("version", v3);
        ResponseEntity<Map> locked = call(HttpMethod.PUT, "/api/platform/contracts/" + id, operator, price, Map.class);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(locked.getBody().get("code")).isEqualTo("CONTRACT_NOT_EDITABLE");
        assertThat(call(HttpMethod.GET, "/api/platform/contracts/" + UUID.randomUUID(), operator, null, Map.class)
                .getBody().get("code")).isEqualTo("CONTRACT_NOT_FOUND");
        // kích hoạt lần hai
        assertThat(activate(id).getBody().get("code")).isEqualTo("CONTRACT_NOT_EDITABLE");
        // quản trị viên tenant không gọi được API platform
        String tenantAdmin = tenantAdminToken("ct-valid");
        assertThat(call(HttpMethod.GET, "/api/platform/contracts/" + id, tenantAdmin, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(historyCount(id, "UPDATED")).isEqualTo(2);
        assertThat(historyCount(id, "CREATED")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void kichHoatChongLanTra409VaRangBuocDbBatNeuBoQuaKiemUngDung() throws Exception {
        UUID tenant = createTenant("ct-overlap");
        UUID c1 = activeContract(tenant, "C1", List.of("LENDING"), today().minusDays(1), today().plusDays(30));
        UUID c2 = draft(tenant, "C2", List.of("LENDING"), today().plusDays(10), today().plusDays(60));
        ResponseEntity<Map> overlap = activate(c2);
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(overlap.getBody().get("code")).isEqualTo("CONTRACT_OVERLAP");
        assertThat(contractStatus(c2)).isEqualTo("DRAFT");

        // bỏ qua kiểm ứng dụng: EXCLUDE trong DB bắt (kết nối superuser)
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("UPDATE tenant_contracts SET status = 'ACTIVE' WHERE id = '" + c2 + "'"))
                    .hasMessageContaining("ex_tenant_contracts_active_overlap");
        }
        // tenant khác không bị ảnh hưởng
        UUID other = createTenant("ct-overlap-b");
        activeContract(other, "B1", List.of("LENDING"), today().minusDays(1), today().plusDays(30));
        // nối tiếp liền kề (nửa mở) được
        UUID c3 = draft(tenant, "C3", List.of("LENDING"), today().plusDays(31), today().plusDays(60));
        assertThat(activate(c3).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contractStatus(c1)).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- áp dụng module, nối tiếp, hết hạn

    @Test
    @SuppressWarnings("rawtypes")
    void kichHoatBatModuleCheMauKhongNhanBanVaJobChayHaiLanNhuMotLan() {
        UUID tenant = createTenant("ct-apply");
        List<String> salesTemplates = jdbc.queryForList("SELECT name FROM role_templates WHERE module = 'SALES'", String.class);
        UUID c = activeContract(tenant, "Gói", List.of("LENDING", "SALES"), today().minusDays(1), today().plusDays(60));

        assertThat(enabledModules(tenant)).containsExactly("LENDING", "SALES");
        assertThat(roleNames(tenant)).containsAll(salesTemplates);
        assertThat(jdbc.queryForObject("SELECT modules_applied_at IS NOT NULL FROM tenant_contracts WHERE id = ?",
                Boolean.class, c)).isTrue();
        assertThat(historyCount(c, "MODULES_APPLIED")).isEqualTo(1);
        int roles = roleNames(tenant).size();

        ProcessContractLifecycleUseCase.Result first = run(at(today()));
        ProcessContractLifecycleUseCase.Result second = run(at(today()));
        assertThat(roleNames(tenant)).hasSize(roles);
        assertThat(historyCount(c, "MODULES_APPLIED")).isEqualTo(1);
        assertThat(first.applied() + second.applied()).as("đã áp dụng ngay lúc kích hoạt").isZero();
        assertThat(historyCount(c, "EXPIRED")).isZero();
    }

    @Test
    @SuppressWarnings("rawtypes")
    void giaHanNoiTiepKichHoatSomKhongLamTatModule() {
        UUID tenant = createTenant("ct-renew");
        UUID c1 = activeContract(tenant, "C1", List.of("LENDING"), today().minusDays(1), today().plusDays(5));

        ResponseEntity<Map> renewed = platformPost("/api/platform/contracts/" + c1 + "/renew",
                Map.of("endDate", today().plusDays(40).toString(), "totalAmount", 2_000_000));
        assertThat(renewed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(renewed.getBody()).containsEntry("status", "DRAFT").containsEntry("startDate", today().plusDays(6).toString());
        UUID c2 = UUID.fromString((String) renewed.getBody().get("id"));
        assertThat((List) renewed.getBody().get("modules")).containsExactly("LENDING");
        assertThat(activate(c2).getBody()).containsEntry("phase", "UPCOMING");
        assertThat(jdbc.queryForObject("SELECT modules_applied_at IS NULL FROM tenant_contracts WHERE id = ?",
                Boolean.class, c2)).as("chưa tới ngày bắt đầu: chưa áp dụng").isTrue();

        // sau ân hạn của C1 (hết ngày today+5, ân hạn 7 ngày): áp dụng C2 TRƯỚC rồi mới hết hạn C1
        ProcessContractLifecycleUseCase.Result result = run(at(today().plusDays(13)));
        assertThat(result.applied()).isGreaterThanOrEqualTo(1);
        assertThat(contractStatus(c1)).isEqualTo("EXPIRED");
        assertThat(contractStatus(c2)).isEqualTo("ACTIVE");
        assertThat(enabledModules(tenant)).containsExactly("LENDING");
        assertThat(tenantState(tenant)).isEqualTo("ACTIVE/-");
        assertThat(jdbc.queryForObject("SELECT retained_module IS NULL FROM tenant_contracts WHERE id = ?", Boolean.class, c1))
                .isTrue();
    }

    @Test
    void quaAnHanChiTatModuleCuaHopDongGiuModuleNgoaiHopDongVaDuLieuRole() {
        UUID tenant = createTenant("ct-expire");
        int rolesBefore = roleNames(tenant).size();
        // tenant đang có LENDING (ngoài hợp đồng); hợp đồng chỉ bao SALES
        UUID c = activeContract(tenant, "Chỉ SALES", List.of("SALES"), today().minusDays(10), today().minusDays(1));
        assertThat(enabledModules(tenant)).containsExactly("LENDING", "SALES");
        int rolesWithSales = roleNames(tenant).size();
        assertThat(rolesWithSales).isGreaterThan(rolesBefore);

        run(at(today().plusDays(6)));
        assertThat(contractStatus(c)).as("còn trong ân hạn").isEqualTo("ACTIVE");
        assertThat(enabledModules(tenant)).containsExactly("LENDING", "SALES");

        run(at(today().plusDays(7)));
        assertThat(contractStatus(c)).isEqualTo("EXPIRED");
        assertThat(enabledModules(tenant)).as("tắt đúng SALES, LENDING bật tay ngoài hợp đồng được giữ").containsExactly("LENDING");
        assertThat(tenantState(tenant)).isEqualTo("ACTIVE/-");
        assertThat(roleNames(tenant)).as("giữ role/dữ liệu").hasSize(rolesWithSales);
        assertThat(historyCount(c, "EXPIRED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_type || '/' || (actor_id IS NULL) FROM tenant_contract_history "
                + "WHERE contract_id = ? AND type = 'EXPIRED'", String.class, c)).isEqualTo("SYSTEM/true");
    }

    @Test
    void moduleCuoiCungGiuMotVaSuspendKhongBao409() {
        Suspended s = suspendedByExpiry("ct-last");
        assertThat(enabledModules(s.tenant())).as("module giữ lại vẫn bật, bất biến M1 không bị phá").containsExactly("LENDING");
        assertThat(jdbc.queryForObject("SELECT retained_module FROM tenant_contracts WHERE id = ?", String.class,
                s.expired())).isEqualTo("LENDING");
        assertThat(jdbc.queryForObject("SELECT suspended_by_contract_id FROM tenants WHERE id = ?", UUID.class,
                s.tenant())).isEqualTo(s.expired());
        assertThat(loginTenant(s.code()).getStatusCode()).as("tenant SUSPENDED không đăng nhập được").isNotEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- tự kích hoạt lại đúng nguyên nhân

    @Test
    @SuppressWarnings("rawtypes")
    void tuMoLaiKhiHopDongMoiToiNgayChiSauSuspendDoHetHan() {
        Suspended s = suspendedByExpiry("ct-reopen");
        UUID c2 = activeContract(s.tenant(), "C2", List.of("LENDING"), today().plusDays(8), today().plusDays(40));
        // (b) kích hoạt hợp đồng có ngày bắt đầu tương lai: tenant VẪN SUSPENDED tới khi job áp dụng
        assertThat(tenantState(s.tenant())).isEqualTo("SUSPENDED/CONTRACT_EXPIRED");
        run(at(today().plusDays(7)));
        assertThat(tenantState(s.tenant())).isEqualTo("SUSPENDED/CONTRACT_EXPIRED");

        // (a) tới starts_at: áp dụng module, tenant ACTIVE, nguyên nhân NULL, đăng nhập lại được
        run(at(today().plusDays(8)));
        assertThat(tenantState(s.tenant())).isEqualTo("ACTIVE/-");
        assertThat(jdbc.queryForObject("SELECT suspended_by_contract_id IS NULL FROM tenants WHERE id = ?", Boolean.class,
                s.tenant())).isTrue();
        assertThat(enabledModules(s.tenant())).containsExactly("LENDING");
        assertThat(jdbc.queryForObject("SELECT retained_module IS NULL FROM tenant_contracts WHERE id = ?", Boolean.class,
                s.expired())).isTrue();
        assertThat(jdbc.queryForObject("SELECT data ->> 'tenantReactivated' FROM tenant_contract_history WHERE contract_id = ? "
                + "AND type = 'MODULES_APPLIED'", String.class, c2)).isEqualTo("true");
        assertThat(loginTenant(s.code()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void suspendTayManualKhongBaoGioTuMoKeCaSauChuoiActivateTayVaSuspendTay() {
        // (c) suspend tay rồi hợp đồng mới áp dụng
        UUID tenant = createTenant("ct-manual");
        activeContract(tenant, "C1", List.of("LENDING"), today().minusDays(1), today());
        ResponseEntity<Map> suspended = platformPost("/api/platform/tenants/" + tenant + "/suspend", null);
        assertThat(suspended.getBody()).containsEntry("status", "SUSPENDED").containsEntry("suspensionCause", "MANUAL");
        UUID c2 = activeContract(tenant, "C2", List.of("LENDING"), today().plusDays(1), today().plusDays(30));
        run(at(today().plusDays(1)));
        assertThat(contractStatus(c2)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT modules_applied_at IS NOT NULL FROM tenant_contracts WHERE id = ?",
                Boolean.class, c2)).isTrue();
        assertThat(tenantState(tenant)).isEqualTo("SUSPENDED/MANUAL");

        // (d) job suspend -> activate tay -> suspend tay -> hợp đồng mới áp dụng -> vẫn SUSPENDED
        Suspended s = suspendedByExpiry("ct-chain");
        ResponseEntity<Map> activated = platformPost("/api/platform/tenants/" + s.tenant() + "/activate", null);
        assertThat(activated.getBody()).containsEntry("status", "ACTIVE");
        assertThat(activated.getBody().get("suspensionCause")).isNull();
        assertThat(tenantState(s.tenant())).isEqualTo("ACTIVE/-");
        platformPost("/api/platform/tenants/" + s.tenant() + "/suspend", null);
        activeContract(s.tenant(), "C2", List.of("LENDING"), today().plusDays(8), today().plusDays(40));
        run(at(today().plusDays(8)));
        assertThat(tenantState(s.tenant())).isEqualTo("SUSPENDED/MANUAL");

        // (e) job suspend rồi platform admin suspend tay thêm -> đổi thành MANUAL, hợp đồng mới không mở
        Suspended e = suspendedByExpiry("ct-escalate");
        assertThat(platformPost("/api/platform/tenants/" + e.tenant() + "/suspend", null).getBody())
                .containsEntry("suspensionCause", "MANUAL");
        assertThat(jdbc.queryForObject("SELECT suspended_by_contract_id IS NULL FROM tenants WHERE id = ?", Boolean.class,
                e.tenant())).isTrue();
        activeContract(e.tenant(), "C2", List.of("LENDING"), today().plusDays(8), today().plusDays(40));
        run(at(today().plusDays(8)));
        assertThat(tenantState(e.tenant())).isEqualTo("SUSPENDED/MANUAL");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void huyHopDongDangApDungTatModuleSuspendCancelledVaKhongTuMo() {
        UUID tenant = createTenant("ct-cancel");
        UUID c1 = activeContract(tenant, "C1", List.of("LENDING"), today().minusDays(1), today().plusDays(60));
        ResponseEntity<Map> noReason = platformPost("/api/platform/contracts/" + c1 + "/cancel", Map.of("reason", " "));
        assertThat(noReason.getBody().get("code")).isEqualTo("INVALID_CONTRACT");

        ResponseEntity<Map> cancelled = platformPost("/api/platform/contracts/" + c1 + "/cancel",
                Map.of("reason", "Khách chấm dứt"));
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED").containsEntry("cancelReason", "Khách chấm dứt")
                .containsEntry("retainedModule", "LENDING");
        assertThat(tenantState(tenant)).isEqualTo("SUSPENDED/CONTRACT_CANCELLED");
        assertThat(enabledModules(tenant)).containsExactly("LENDING");

        // (f) hợp đồng mới áp dụng KHÔNG tự mở khi suspend do hủy
        UUID c2 = activeContract(tenant, "C2", List.of("LENDING"), today().plusDays(1), today().plusDays(40));
        run(at(today().plusDays(1)));
        assertThat(contractStatus(c2)).isEqualTo("ACTIVE");
        assertThat(tenantState(tenant)).isEqualTo("SUSPENDED/CONTRACT_CANCELLED");
        assertThat(platformPost("/api/platform/contracts/" + c1 + "/cancel", Map.of("reason", "lại")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // hủy NHÁP chỉ đổi trạng thái
        UUID draft = draft(tenant, "D", List.of("LENDING"), today().plusDays(100), today().plusDays(130));
        assertThat(platformPost("/api/platform/contracts/" + draft + "/cancel", Map.of("reason", "bỏ")).getBody())
                .containsEntry("status", "CANCELLED");
    }

    @Test
    void suspendTayXenJobApDungDongThoiKetQuaNhatQuan() throws Exception {
        Suspended s = suspendedByExpiry("ct-race");
        activeContract(s.tenant(), "C2", List.of("LENDING"), today().plusDays(8), today().plusDays(40));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> jobRun = pool.submit(() -> {
            go.await();
            return job.runOnce(at(today().plusDays(8)));
        });
        Future<?> manual = pool.submit(() -> {
            go.await();
            return platformPost("/api/platform/tenants/" + s.tenant() + "/suspend", null);
        });
        go.countDown();
        jobRun.get();
        manual.get();
        pool.shutdown();
        // hoặc suspend tay trước (không mở) hoặc job mở rồi suspend tay: cả hai thứ tự đều kết thúc MANUAL
        assertThat(tenantState(s.tenant())).isEqualTo("SUSPENDED/MANUAL");
    }

    // ---------------------------------------------------------------- job: song song, lỗi, legacy

    @Test
    void haiLuongChayJobCungLucChiXuLyMotLan() throws Exception {
        UUID tenant = createTenant("ct-parallel");
        UUID c1 = activeContract(tenant, "C1", List.of("LENDING"), today().minusDays(10), today().minusDays(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ProcessContractLifecycleUseCase.Result>> runs = List.of(
                pool.submit(() -> { go.await(); return job.runOnce(at(today().plusDays(7))); }),
                pool.submit(() -> { go.await(); return job.runOnce(at(today().plusDays(7))); }));
        go.countDown();
        for (Future<ProcessContractLifecycleUseCase.Result> f : runs) assertThat(f.get().failed()).isZero();
        pool.shutdown();

        assertThat(historyCount(c1, "EXPIRED")).isEqualTo(1);
        assertThat(tenantState(tenant)).isEqualTo("SUSPENDED/CONTRACT_EXPIRED");
        run(at(today().plusDays(7)));
        assertThat(historyCount(c1, "EXPIRED")).as("lượt thứ ba là no-op").isEqualTo(1);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void hopDongLegacyBiCatKhiKichHoatHopDongMoiVaHetAnHanNhuHopDongThuong() throws Exception {
        UUID tenant = createTenant("ct-legacy");
        UUID legacy = UUID.randomUUID();
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenant_contracts (id, tenant_id, name, status, legacy, starts_at, ends_at, total_amount, "
                    + "modules_applied_at) VALUES ('" + legacy + "', '" + tenant + "', 'Hợp đồng chuyển tiếp', 'ACTIVE', TRUE, "
                    + "now() - interval '2 day', NULL, 0, now())");
            s.execute("INSERT INTO tenant_contract_modules (contract_id, module) VALUES ('" + legacy + "', 'LENDING')");
        }
        // hợp đồng mới không bắt đầu sau legacy: 409
        UUID early = draft(tenant, "Sớm", List.of("LENDING"), today().minusDays(5), today().plusDays(30));
        assertThat(activate(early).getBody().get("code")).isEqualTo("CONTRACT_OVERLAP");

        UUID c = draft(tenant, "Mới", List.of("LENDING", "SALES"), today().plusDays(1), today().plusDays(60));
        assertThat(activate(c).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT ends_at IS NOT NULL FROM tenant_contracts WHERE id = ?", Boolean.class, legacy))
                .isTrue();
        assertThat(historyCount(legacy, "LEGACY_SUPERSEDED")).isEqualTo(1);

        run(at(today().plusDays(1)));
        assertThat(enabledModules(tenant)).containsExactly("LENDING", "SALES");
        run(at(today().plusDays(9)));
        assertThat(contractStatus(legacy)).isEqualTo("EXPIRED");
        assertThat(enabledModules(tenant)).as("LENDING được hợp đồng mới bao phủ nên không bị tắt").containsExactly("LENDING", "SALES");
        assertThat(tenantState(tenant)).isEqualTo("ACTIVE/-");
    }

    // ---------------------------------------------------------------- thao tác tay, thanh toán

    @Test
    @SuppressWarnings("rawtypes")
    void thaoTacTayGhiLichSuNgoaiHopDongModuleTatTayKhongBiBatLai() {
        UUID tenant = createTenant("ct-manualmod");
        UUID c = activeContract(tenant, "C", List.of("LENDING"), today().minusDays(1), today().plusDays(60));
        String operator = platformToken();

        call(HttpMethod.PUT, "/api/platform/tenants/" + tenant + "/modules/SALES", operator, null, Map.class);
        assertThat(jdbc.queryForObject("SELECT data ->> 'outsideContract' FROM tenant_contract_history WHERE tenant_id = ? "
                + "AND type = 'MANUAL_MODULE_ENABLED'", String.class, tenant)).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT contract_id FROM tenant_contract_history WHERE tenant_id = ? "
                + "AND type = 'MANUAL_MODULE_ENABLED'", UUID.class, tenant)).isEqualTo(c);

        call(HttpMethod.DELETE, "/api/platform/tenants/" + tenant + "/modules/LENDING", operator, null, Map.class);
        assertThat(jdbc.queryForObject("SELECT data ->> 'outsideContract' FROM tenant_contract_history WHERE tenant_id = ? "
                + "AND type = 'MANUAL_MODULE_DISABLED'", String.class, tenant)).isEqualTo("false");
        run(at(today()));
        assertThat(enabledModules(tenant)).as("job không bật lại module bị tắt tay").containsExactly("SALES");

        ResponseEntity<Map> list = call(HttpMethod.GET, "/api/platform/tenants/" + tenant + "/contracts", operator, null,
                Map.class);
        assertThat((List) list.getBody().get("modulesOutsideContract")).containsExactly("SALES");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void thanhToanBatBienDaoToanPhanVaVaiTroUngDungKhongSuaXoaDuoc() throws Exception {
        UUID tenant = createTenant("ct-pay");
        String operator = platformToken();
        UUID draftId = draft(tenant, "D", List.of("LENDING"), today().plusDays(1), today().plusDays(30));
        ResponseEntity<Map> notAllowed = platformPost("/api/platform/contracts/" + draftId + "/payments",
                Map.of("paidOn", today().toString(), "amount", 100));
        assertThat(notAllowed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notAllowed.getBody().get("code")).isEqualTo("PAYMENT_NOT_ALLOWED");

        UUID c = activeContract(tenant, "C", List.of("LENDING"), today().minusDays(1), today().plusDays(30));
        String base = "/api/platform/contracts/" + c + "/payments";
        assertThat(platformPost(base, Map.of("paidOn", today().toString(), "amount", 0)).getBody().get("code"))
                .isEqualTo("INVALID_PAYMENT");
        ResponseEntity<Map> p1 = platformPost(base, Map.of("paidOn", today().toString(), "amount", 400_000,
                "reference", "CK-001", "note", "đợt 1"));
        assertThat(p1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID payment = UUID.fromString((String) p1.getBody().get("id"));
        assertThat(call(HttpMethod.GET, "/api/platform/contracts/" + c, operator, null, Map.class).getBody())
                .containsEntry("paidAmount", 400_000).containsEntry("outstandingAmount", 600_000);

        ResponseEntity<Map> reversal = platformPost(base + "/" + payment + "/reverse", Map.of("note", "nhập nhầm"));
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reversal.getBody()).containsEntry("amount", -400_000).containsEntry("reversesPaymentId", payment.toString());
        UUID reversalId = UUID.fromString((String) reversal.getBody().get("id"));
        assertThat(platformPost(base + "/" + payment + "/reverse", Map.of()).getBody().get("code"))
                .isEqualTo("PAYMENT_ALREADY_REVERSED");
        assertThat(platformPost(base + "/" + reversalId + "/reverse", Map.of()).getBody().get("code"))
                .isEqualTo("PAYMENT_NOT_REVERSIBLE");
        assertThat(platformPost(base + "/" + UUID.randomUUID() + "/reverse", Map.of()).getBody().get("code"))
                .isEqualTo("PAYMENT_NOT_FOUND");
        // thanh toán của hợp đồng khác trong đường dẫn: 404
        UUID other = activeContract(createTenant("ct-pay-b"), "O", List.of("LENDING"), today().minusDays(1), today().plusDays(30));
        assertThat(platformPost("/api/platform/contracts/" + other + "/payments/" + payment + "/reverse", Map.of())
                .getBody().get("code")).isEqualTo("PAYMENT_NOT_FOUND");

        ResponseEntity<Map> detail = call(HttpMethod.GET, "/api/platform/contracts/" + c, operator, null, Map.class);
        assertThat(detail.getBody()).containsEntry("paidAmount", 0).containsEntry("outstandingAmount", 1_000_000);
        List<Map<String, Object>> payments = (List<Map<String, Object>>) detail.getBody().get("payments");
        assertThat(payments).hasSize(2);
        assertThat(payments.get(0).get("reversedByPaymentId")).isEqualTo(reversalId.toString());
        // thanh toán sau khi hết hạn/hủy vẫn ghi được
        platformPost("/api/platform/contracts/" + c + "/cancel", Map.of("reason", "kết thúc"));
        assertThat(platformPost(base, Map.of("paidOn", today().toString(), "amount", 50)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // ứng dụng (identity_app) không sửa/xóa được; REVOKE + trigger
        assertThatThrownBy(() -> jdbc.update("UPDATE tenant_contract_payments SET amount = 1 WHERE id = ?", payment))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM tenant_contract_payments WHERE id = ?", payment))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("UPDATE tenant_contract_history SET type = 'X' WHERE contract_id = ?", c))
                .rootCause().hasMessageContaining("permission denied");
        try (Connection c2 = AppRoleDatabase.superuser(postgres); Statement s = c2.createStatement()) {
            assertThatThrownBy(() -> s.execute("DELETE FROM tenant_contract_payments WHERE id = '" + payment + "'"))
                    .hasMessageContaining("chỉ thêm");
        }
        assertThat(historyCount(c, "PAYMENT_RECORDED")).isEqualTo(2);
        assertThat(historyCount(c, "PAYMENT_REVERSED")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- nhắc hạn

    @Test
    void nhacHanMocGapNhatMoiMocMotLanKeoDaiMoLaiMocVaDanhDauBoQua() {
        UUID tenant = createTenant("ct-remind");
        UUID c = activeContract(tenant, "Gói nhắc", List.of("LENDING"), today().minusDays(1), today().plusDays(20));

        run(at(today()));
        assertThat(jdbc.queryForObject("SELECT days_before || '/' || skipped || '/' || recipient_count FROM "
                + "tenant_contract_reminders WHERE contract_id = ?", String.class, c)).isEqualTo("30/false/1");
        assertThat(historyCount(c, "REMINDER_SENT")).isEqualTo(1);
        run(at(today()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_contract_reminders WHERE contract_id = ?",
                Integer.class, c)).as("không gửi lại").isEqualTo(1);

        // kéo dài ngày kết thúc: mốc tự mở lại (khóa có ends_at)
        ResponseEntity<Map> extended = platformPost("/api/platform/contracts/" + c + "/extend",
                Map.of("endDate", today().plusDays(25).toString(), "version",
                        jdbc.queryForObject("SELECT version FROM tenant_contracts WHERE id = ?", Long.class, c)));
        assertThat(extended.getStatusCode()).isEqualTo(HttpStatus.OK);
        run(at(today()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_contract_reminders WHERE contract_id = ?",
                Integer.class, c)).isEqualTo(2);

        // job ngừng lâu: còn 3 ngày thì gửi mốc 7, đánh dấu bỏ qua mốc 30
        UUID near = activeContract(createTenant("ct-remind-b"), "Sắp hết", List.of("LENDING"), today().minusDays(1),
                today().plusDays(3));
        run(at(today()));
        assertThat(jdbc.queryForList("SELECT days_before || '/' || skipped FROM tenant_contract_reminders WHERE contract_id = ? "
                + "ORDER BY days_before", String.class, near)).containsExactly("7/false", "30/true");
    }

    // ---------------------------------------------------------------- tenant admin chỉ đọc, cách ly

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void tenantAdminChiThayHopDongCuaMinhKhongLoTruongNoiBoVaCachLyHaiTenant() {
        UUID a = createTenant("ct-iso-a");
        UUID b = createTenant("ct-iso-b");
        UUID noContract = UUID.randomUUID();
        createTenant("ct-iso-c");
        UUID ca = activeContract(a, "Hợp đồng A", List.of("LENDING"), today().minusDays(1), today().plusDays(60));
        UUID draftA = draft(a, "Nháp A", List.of("LENDING"), today().plusDays(100), today().plusDays(130));
        UUID cb = activeContract(b, "Hợp đồng B", List.of("LENDING"), today().minusDays(1), today().plusDays(60));
        platformPost("/api/platform/contracts/" + ca + "/payments", Map.of("paidOn", today().toString(), "amount", 300_000,
                "reference", "BÍ-MẬT-A"));

        String tokenA = tenantAdminToken("ct-iso-a");
        ResponseEntity<String> listA = call(HttpMethod.GET, "/internal/contracts", tokenA, null, String.class);
        assertThat(listA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listA.getBody()).contains(ca.toString()).doesNotContain(cb.toString()).doesNotContain(draftA.toString());
        assertThat(listA.getBody()).doesNotContain("internalNote", "ghi chú nội bộ", "payments", "BÍ-MẬT-A", "legacy",
                "retainedModule", "modulesOutsideContract", "cancelReason");
        ResponseEntity<Map> currentA = call(HttpMethod.GET, "/internal/contracts/current", tokenA, null, Map.class);
        assertThat(currentA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(currentA.getBody()).containsEntry("id", ca.toString()).containsEntry("phase", "IN_EFFECT")
                .containsEntry("totalAmount", 1_000_000).containsEntry("paidAmount", 300_000)
                .containsEntry("outstandingAmount", 700_000).containsEntry("inGrace", false);

        String tokenB = tenantAdminToken("ct-iso-b");
        ResponseEntity<String> listB = call(HttpMethod.GET, "/internal/contracts", tokenB, null, String.class);
        assertThat(listB.getBody()).contains(cb.toString()).doesNotContain(ca.toString());

        // tenant không có hợp đồng: 404 CONTRACT_NOT_FOUND
        String tokenC = tenantAdminToken("ct-iso-c");
        ResponseEntity<Map> none = call(HttpMethod.GET, "/internal/contracts/current", tokenC, null, Map.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(none.getBody().get("code")).isEqualTo("CONTRACT_NOT_FOUND");
        assertThat(call(HttpMethod.GET, "/internal/contracts", tokenC, null, List.class).getBody()).isEmpty();
        // token nền tảng (không tenantId): không đọc được API tenant
        assertThat(call(HttpMethod.GET, "/internal/contracts", platformToken(), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noContract).isNotNull();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void trangSapHetHanVaChiTietTenantCoSuspensionCause() {
        UUID tenant = createTenant("ct-expiring");
        UUID soon = activeContract(tenant, "Sắp hết", List.of("LENDING"), today().minusDays(1), today().plusDays(10));
        String operator = platformToken();
        ResponseEntity<List> expiring = call(HttpMethod.GET, "/api/platform/contracts/expiring?withinDays=30", operator,
                null, List.class);
        assertThat(expiring.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = expiring.getBody();
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.get("contractId")).isEqualTo(soon.toString());
            assertThat(r.get("tenantCode")).isEqualTo("ct-expiring");
            assertThat(r.get("daysLeft")).isEqualTo(10);
            assertThat(r.get("inGrace")).isEqualTo(false);
        });
        assertThat(call(HttpMethod.GET, "/api/platform/contracts/expiring?withinDays=0", operator, null, Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        platformPost("/api/platform/tenants/" + tenant + "/suspend", null);
        ResponseEntity<Map> detail = call(HttpMethod.GET, "/api/platform/tenants/" + tenant, operator, null, Map.class);
        assertThat(detail.getBody()).containsEntry("status", "SUSPENDED").containsEntry("suspensionCause", "MANUAL");
        ResponseEntity<List> history = call(HttpMethod.GET, "/api/platform/contracts/" + soon + "/history", operator, null,
                List.class);
        assertThat(((List<Map<String, Object>>) history.getBody())).extracting(h -> h.get("type"))
                .contains("CREATED", "ACTIVATED", "MODULES_APPLIED");
    }
}
