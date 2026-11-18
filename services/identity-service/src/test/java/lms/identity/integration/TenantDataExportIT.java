package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lms.identity.tenantdata.application.RunTenantDataExportUseCase;
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
 * Xuất dữ liệu tenant (ADR-0031 F2) trên PostgreSQL thật, ứng dụng chạy bằng vai trò identity_app (chịu RLS): migration V41,
 * RLS hai tenant ở phần identity (A không thấy dữ liệu của B trong gói), không có băm mật khẩu/secret trong gói, một yêu
 * cầu đang chạy mỗi tenant (chỉ mục duy nhất một phần), giới hạn 24 giờ, IDOR (404 cùng thân), file mã hóa trên đĩa. Các
 * nguồn catalog/circulation/support/sales để trống (ghi "chưa cấu hình" vào manifest): chạy end-to-end với exporter thật là việc
 * của bài kiểm thử tích hợp liên service. Job nền tắt; test gọi thẳng {@code run(id)}. Cần Docker. Chạy: mvn -pl
 * services/identity-service -am test -Dtest=TenantDataExportIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44
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
        "app.internal-api-key=test-internal-key",
        "lms.contract.job.enabled=false",
        "lms.tenant-data.export.enabled=false",
        "lms.tenant-data.export.retry-backoff=PT0S",
        "lms.tenant-data.export.sources.catalog.base-url=",
        "lms.tenant-data.export.sources.circulation.base-url=",
        "lms.tenant-data.export.sources.support.base-url=",
        "lms.tenant-data.export.sources.sales.base-url="
})
@Testcontainers
class TenantDataExportIT {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final Path DIR = tempDir();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Path tempDir() {
        try {
            return Files.createTempDirectory("tenant-exports-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("lms.tenant-data.export.encryption-key", () -> Base64.getEncoder().encodeToString(key));
        registry.add("lms.tenant-data.export.dir", DIR::toString);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunTenantDataExportUseCase runner;

    private static final String BASE = "/internal/tenant-data/exports";

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void haiTenantChiThayDuLieuCuaMinhTrongGoiVaIdorTraNotFound() throws Exception {
        CrossTenantFixture fx = new CrossTenantFixture(rest, jdbc, postgres);
        String adminA = fx.token("admin", "Admin@12345");
        UUID tenantB = fx.newTenant("export-b");
        String adminB = fx.tenantBAdmin(tenantB, "export-b", adminA);
        fx.createUserIn(adminA, "only-in-a-user");
        fx.createUserIn(adminB, "only-in-b-user");

        // yêu cầu: 202 PENDING; yêu cầu thứ hai khi còn đang chạy bị 409 (chỉ mục duy nhất một phần)
        ResponseEntity<Map> a = fx.call(HttpMethod.POST, BASE, adminA, Map.of("reason", "chuyển hệ thống"), Map.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(a.getBody().get("status")).isEqualTo("PENDING");
        assertThat(fx.call(HttpMethod.POST, BASE, adminA, Map.of(), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> b = fx.call(HttpMethod.POST, BASE, adminB, null, Map.class);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID idA = UUID.fromString((String) a.getBody().get("id"));
        UUID idB = UUID.fromString((String) b.getBody().get("id"));

        // chạy job (worker): cả hai thành READY
        assertThat(runner.run(idA)).isTrue();
        assertThat(runner.run(idB)).isTrue();
        Map<String, Object> viewA = fx.call(HttpMethod.GET, BASE + "/" + idA, adminA, null, Map.class).getBody();
        assertThat(viewA.get("status")).isEqualTo("READY");
        assertThat(((Number) viewA.get("partsDone")).intValue()).isEqualTo(1);
        assertThat(((Number) viewA.get("sizeBytes")).longValue()).isPositive();
        assertThat(viewA.toString()).doesNotContain(".zip.enc");

        // giới hạn 24 giờ: lần thứ hai sau khi thành công bị 429
        assertThat(fx.call(HttpMethod.POST, BASE, adminA, Map.of(), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // IDOR: A không xem, không tải được gói của B (404 cùng thân với id không tồn tại)
        ResponseEntity<String> cross = fx.call(HttpMethod.GET, BASE + "/" + idB, adminA, null, String.class);
        ResponseEntity<String> missing = fx.call(HttpMethod.GET, BASE + "/" + UUID.randomUUID(), adminA, null,
                String.class);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stripTimestamp(cross.getBody())).isEqualTo(stripTimestamp(missing.getBody()));
        assertThat(fx.call(HttpMethod.GET, BASE + "/" + idB + "/download", adminA, null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((java.util.List<?>) fx.call(HttpMethod.GET, BASE, adminA, null, java.util.List.class).getBody()))
                .hasSize(1);

        // tải gói của A: chỉ dữ liệu của A, không có băm mật khẩu/secret
        ResponseEntity<byte[]> zipA = fx.call(HttpMethod.GET, BASE + "/" + idA + "/download", adminA, null,
                byte[].class);
        assertThat(zipA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(zipA.getHeaders().getFirst("Cache-Control")).contains("no-store");
        Map<String, String> filesA = unzip(zipA.getBody());
        assertThat(filesA).containsKeys("identity/users.csv", "identity/roles.csv", "identity/tenant_memberships.csv",
                "identity/reader_profiles.csv", "manifest.json", "manifest.json.sig", "README.txt");
        assertThat(filesA.get("identity/users.csv")).contains("only-in-a-user").doesNotContain("only-in-b-user");
        assertThat(filesA.get("identity/roles.csv")).doesNotContain("B_ADMIN");
        for (Map.Entry<String, String> f : filesA.entrySet()) {
            assertThat(f.getValue()).as(f.getKey()).doesNotContain("$2a$").doesNotContain("$2b$")
                    .doesNotContain("secret_hash").doesNotContain("site_key,");
        }
        assertThat(filesA.get("manifest.json")).contains("\"notConfigured\"").contains("catalog")
                .contains(TENANT_A.toString()).doesNotContain(tenantB.toString());

        Map<String, String> filesB = unzip(fx.call(HttpMethod.GET, BASE + "/" + idB + "/download", adminB, null,
                byte[].class).getBody());
        assertThat(filesB.get("identity/users.csv")).contains("only-in-b-user").doesNotContain("only-in-a-user");
        assertThat(filesB.get("identity/roles.csv")).contains("B_ADMIN");

        // số lần tải ghi nhận
        assertThat(jdbc.queryForObject("SELECT download_count FROM tenant_data_exports WHERE id = ?", Integer.class,
                idA)).isEqualTo(1);

        // file trên đĩa được mã hóa: không phải ZIP thô, không chứa nội dung rõ
        try (var files = Files.list(DIR)) {
            for (Path p : files.toList()) {
                byte[] raw = Files.readAllBytes(p);
                assertThat(new String(raw, StandardCharsets.ISO_8859_1)).as(p.getFileName().toString())
                        .doesNotContain("only-in-a-user").doesNotContain("only-in-b-user");
            }
        }
        assertThat(Files.list(DIR).map(p -> p.getFileName().toString()).toList())
                .allMatch(name -> name.startsWith("export-") && name.endsWith(".zip.enc"));
    }

    @Test
    void nguoiDungThuongKhongCoKhoaBi403() {
        CrossTenantFixture fx = new CrossTenantFixture(rest, jdbc, postgres);
        String adminA = fx.token("admin", "Admin@12345");
        // khóa động chưa gán cho role nào: người không phải admin bị 403 (khóa vẫn được tự đăng ký)
        fx.call(HttpMethod.GET, BASE, adminA, null, String.class);
        String plain = fx.plainUserOfA(adminA, "plain-export-user", "TenantDataExportController/get");
        assertThat(fx.call(HttpMethod.GET, BASE, plain, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.call(HttpMethod.POST, BASE, plain, Map.of(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fx.call(HttpMethod.GET, BASE, null, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bangMoiCoChiMucDuyNhatMotPhanVaKhongBatRls() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = "
                + "'uq_tenant_data_exports_in_progress'", Integer.class)).isEqualTo(1);
        // bảng toàn cục (job nền quét mọi tenant): không RLS, nằm trong TENANT_EXEMPT_TABLES
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE relname = 'tenant_data_exports'",
                Boolean.class)).isFalse();
    }

    private static String stripTimestamp(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
