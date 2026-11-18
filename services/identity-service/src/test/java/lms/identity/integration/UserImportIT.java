package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportSessionStore;
import lms.imports.engine.InMemoryImportSessionStore;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Nhập người dùng từ Excel trên PostgreSQL thật với vai trò app (RLS bật, ADR-0006): upload (preview) không ghi DB và số
 * câu SQL không tăng theo số dòng; confirm chạy nền ghi đúng tenant; mật khẩu tạm tải một lần và đăng nhập được với
 * mustChangePassword; dòng trùng bị bỏ qua. Phiên preview dùng store trong bộ nhớ (không cần Redis).
 * Cần Docker. Chạy: mvn -pl services/identity-service -am test -Dtest=UserImportIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44 -Dspring.kafka.bootstrap-servers=localhost:1 -Dspring.kafka.consumer.group-id=lms-it-isolated
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/identity",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.admin.properties.request.timeout.ms=500",
        "spring.kafka.admin.properties.default.api.timeout.ms=500",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "lms.cache.enabled=false",
        "auth.bootstrap-admin.password=Admin@12345",
        "app.internal-api-key=test-internal-key"
})
@Testcontainers
@Import(UserImportIT.InMemorySessions.class)
class UserImportIT {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @TestConfiguration
    static class InMemorySessions {
        @Bean
        ImportSessionStore importSessionStore() {
            return new InMemoryImportSessionStore(ImportLimits.defaults(), Clock.systemUTC());
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired SessionFactory sessionFactory;
    private final ObjectMapper json = new ObjectMapper();
    private final List<String> opened = new ArrayList<>();

    /** Giới hạn 3 phiên mở mỗi người dùng: dọn phiên sau mỗi test. */
    @org.junit.jupiter.api.AfterEach
    void closeSessions() {
        String token = adminToken();
        for (String id : opened) {
            try {
                call(HttpMethod.DELETE, "/" + id, token);
            } catch (RuntimeException ignored) {
                // phiên đã xong hoặc đã hủy
            }
        }
        opened.clear();
    }

    /** Đếm bằng superuser (bỏ qua RLS) để kiểm tra dữ liệu của mọi tenant. */
    private static long count(String sql, Object... args) {
        try (java.sql.Connection c = AppRoleDatabase.superuser(postgres); java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private String adminToken() {
        ResponseEntity<Map> r = rest.postForEntity("/internal/auth/login",
                Map.of("username", "admin", "password", "Admin@12345"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    private static byte[] workbook(String prefix, int count, int readerEvery) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("NguoiDung");
            String[] header = {"username", "fullName", "email", "isReader", "faculty"};
            Row h = s.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            for (int i = 0; i < count; i++) {
                Row r = s.createRow(i + 1);
                r.createCell(0).setCellValue(prefix + i);
                r.createCell(1).setCellValue("Họ tên " + prefix + i);
                r.createCell(2).setCellValue(prefix + i + "@import.vn");
                if (readerEvery > 0 && i % readerEvery == 0) {
                    r.createCell(3).setCellValue("Có");
                    r.createCell(4).setCellValue("CNTT");
                }
            }
            wb.createSheet("_meta").createRow(0).createCell(0).setCellValue("type");
            wb.getSheet("_meta").getRow(0).createCell(1).setCellValue("users");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private ResponseEntity<byte[]> call(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/internal/imports/users" + path, method, new HttpEntity<>(headers), byte[].class);
    }

    private JsonNode upload(String token, byte[] file) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return "nguoi-dung.xlsx";
            }
        });
        ResponseEntity<String> r = rest.exchange("/internal/imports/users", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode created = json.readTree(r.getBody());
        opened.add(created.get("importId").asText());
        return created;
    }

    private JsonNode waitCommitted(String token, String id) throws Exception {
        for (int i = 0; i < 600; i++) {
            JsonNode view = json.readTree(call(HttpMethod.GET, "/" + id, token).getBody());
            String status = view.get("status").asText();
            if (status.equals("COMMITTED") || status.equals("COMMIT_FAILED")) return view;
            Thread.sleep(100);
        }
        throw new AssertionError("Phiên không xong kịp thời");
    }

    private long statements() {
        Statistics stats = sessionFactory.getStatistics();
        return stats.getPrepareStatementCount();
    }

    @Test
    void previewDoesNotWriteAndSqlCountDoesNotGrowWithRows() throws Exception {
        String token = adminToken();
        long usersBefore = count("SELECT count(*) FROM users");

        long b1 = statements();
        JsonNode small = upload(token, workbook("smalla", 20, 4));
        long smallCost = statements() - b1;
        long b2 = statements();
        JsonNode large = upload(token, workbook("largea", 400, 4));
        long largeCost = statements() - b2;

        assertThat(small.get("totals").get("valid").asInt()).isEqualTo(20);
        assertThat(large.get("totals").get("valid").asInt()).isEqualTo(400);
        // Cùng số câu SQL cho 20 và 400 dòng: kiểm trùng theo lô (IN), không từng dòng
        assertThat(largeCost).as("câu SQL 400 dòng so với 20 dòng").isEqualTo(smallCost);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(usersBefore);
        call(HttpMethod.DELETE, "/" + small.get("importId").asText(), token);
        call(HttpMethod.DELETE, "/" + large.get("importId").asText(), token);
    }

    @Test
    void confirmWritesUsersAndReadersWithGeneratedPasswordsDownloadedOnce() throws Exception {
        String token = adminToken();
        int count = 60;
        JsonNode view = upload(token, workbook("imp", count, 3));
        String id = view.get("importId").asText();
        assertThat(view.get("totals").get("valid").asInt()).isEqualTo(count);

        ResponseEntity<byte[]> early = call(HttpMethod.GET, "/" + id + "/credentials", token);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<byte[]> confirm = call(HttpMethod.POST, "/" + id + "/confirm", token);
        assertThat(confirm.getStatusCode().value()).isIn(200, 202);
        JsonNode done = waitCommitted(token, id);
        assertThat(done.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(done.get("result").get("created").asInt()).isEqualTo(count);
        assertThat(done.get("result").get("failed").asInt()).isZero();

        assertThat(count("SELECT count(*) FROM users WHERE username LIKE 'imp%'")).isEqualTo(60L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credentials WHERE username LIKE 'imp%' AND must_change_password",
                Long.class)).isEqualTo(60L);
        assertThat(count("SELECT count(*) FROM tenant_memberships m JOIN users u ON u.id = m.user_id "
                + "WHERE u.username LIKE 'imp%' AND m.tenant_id = '00000000-0000-0000-0000-0000000000d1'"))
                .isEqualTo(60L);
        assertThat(count("SELECT count(*) FROM reader_profiles WHERE card_no LIKE 'imp%'"))
                .isEqualTo(20L);
        // Không gán role cho người nhập
        assertThat(count("SELECT count(*) FROM user_roles ur JOIN users u ON u.id = ur.user_id "
                + "WHERE u.username LIKE 'imp%'")).isZero();

        // Mật khẩu tạm: tải một lần, đăng nhập được, và bị buộc đổi mật khẩu
        ResponseEntity<byte[]> creds = call(HttpMethod.GET, "/" + id + "/credentials", token);
        assertThat(creds.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> passwords = new HashMap<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(creds.getBody()))) {
            Sheet s = wb.getSheetAt(0);
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                passwords.put(s.getRow(i).getCell(0).getStringCellValue(), s.getRow(i).getCell(1).getStringCellValue());
            }
        }
        assertThat(passwords).hasSize(count);
        assertThat(call(HttpMethod.GET, "/" + id + "/credentials", token).getStatusCode()).isEqualTo(HttpStatus.GONE);

        ResponseEntity<Map> login = rest.postForEntity("/internal/auth/login",
                Map.of("username", "imp7", "password", passwords.get("imp7")), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().toString()).contains("mustChangePassword=true");
        // Không lưu mật khẩu rõ trong DB
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credentials WHERE password_hash = ?", Long.class,
                passwords.get("imp7"))).isZero();

        // Upload lại cùng file: mọi dòng là DUPLICATE, không ghi thêm
        JsonNode again = upload(token, workbook("imp", count, 3));
        assertThat(again.get("totals").get("duplicate").asInt()).isEqualTo(count);
        assertThat(again.get("totals").get("valid").asInt()).isZero();
        ResponseEntity<byte[]> nothing = call(HttpMethod.POST, "/" + again.get("importId").asText() + "/confirm", token);
        assertThat(nothing.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void invalidAndInFileDuplicateRowsAreReportedAndSkipped() throws Exception {
        String token = adminToken();
        byte[] file;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("NguoiDung");
            String[] header = {"username", "fullName", "email"};
            Row head = s.createRow(0);
            for (int i = 0; i < header.length; i++) head.createCell(i).setCellValue(header[i]);
            List<String[]> rows = new ArrayList<>(List.of(
                    new String[] {"hople1", "Hợp Lệ", "hople1@mix.vn"},
                    new String[] {"HoPle1", "Trùng file", "khac@mix.vn"},
                    new String[] {"hople2", "Email sai", "khong-phai-email"},
                    new String[] {"admin", "Trùng DB", "admin-moi@mix.vn"}));
            for (int i = 0; i < rows.size(); i++) {
                Row r = s.createRow(i + 1);
                for (int c = 0; c < 3; c++) r.createCell(c).setCellValue(rows.get(i)[c]);
            }
            wb.createSheet("_meta").createRow(0).createCell(0).setCellValue("type");
            wb.getSheet("_meta").getRow(0).createCell(1).setCellValue("users");
            wb.write(out);
            file = out.toByteArray();
        }
        JsonNode view = upload(token, file);
        JsonNode totals = view.get("totals");
        assertThat(totals.get("valid").asInt()).isEqualTo(1);
        assertThat(totals.get("invalid").asInt()).isEqualTo(2);
        assertThat(totals.get("duplicate").asInt()).isEqualTo(1);
        String id = view.get("importId").asText();
        call(HttpMethod.POST, "/" + id + "/confirm", token);
        JsonNode done = waitCommitted(token, id);
        assertThat(done.get("result").get("created").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE username LIKE 'hople%'")).isEqualTo(1L);
        assertThat(call(HttpMethod.GET, "/" + id + "/error-file", token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void importPermissionsAreSeededByV31() {
        assertThat(count("SELECT count(*) FROM permissions WHERE code LIKE '%ImportController/%'"))
                .isEqualTo(19L); // 17 khóa của V31 + SeriesImportController/upload|confirm của V36
        assertThat(count("SELECT count(*) FROM permissions WHERE code LIKE 'SeriesImportController/%'")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions WHERE code = 'TitleController/lookupByIsbn'",
                Long.class)).isEqualTo(1L);
        assertThat(count("SELECT count(*) FROM role_template_permissions WHERE permission_code = "
                + "'UserImportController/credentials'")).isEqualTo(1L);
    }
}
