package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.auth.infrastructure.security.HmacOtpCodec;
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
 * Xác minh email bằng OTP (audit A3, V37) trên Postgres thật, dưới vai trò identity_app: validate schema với entity mới,
 * tự đăng ký tạo tài khoản chưa xác minh + OTP chỉ lưu hash, đăng nhập storefront bị chặn EMAIL_NOT_VERIFIED, OTP sai/đúng/
 * dùng lại, hết lượt thử, resend không lộ tài khoản, tài khoản cũ (backfill) và nhân viên không bị khóa. SMTP không chạy
 * trong IT (gửi thư lỗi chỉ log), nên OTP đã biết được ghi đè bằng SQL qua cùng thuật toán băm. Cần Docker.
 * Chạy: mvn -o -pl services/identity-service -am test -Dtest=EmailVerificationIT
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
        "lms.identity.email-verification.enabled=true",
        "lms.identity.email-verification.max-attempts=3",
        "spring.mail.host=localhost",
        "spring.mail.port=1",
        "spring.mail.properties.mail.smtp.connectiontimeout=300",
        "spring.mail.properties.mail.smtp.timeout=300"
})
@Testcontainers
class EmailVerificationIT {

    static final String ADMIN_PASSWORD = "TempAdmin@123";
    static final String PASSWORD = "Secret@12345";
    static final String CONFIRM = "/internal/auth/email-verifications/confirm";
    static final String RESEND = "/internal/auth/email-verifications/resend";
    static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired TestRestTemplate rest;

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
    private UUID createTenant(String code) {
        Map<String, Object> body = new HashMap<>(Map.of("code", code, "name", "Cửa hàng " + code, "newAdmin",
                Map.of("username", code + "-admin", "fullName", "Quản trị " + code, "email", code + "@x.vn",
                        "temporaryPassword", ADMIN_PASSWORD)));
        body.put("modules", List.of("SALES"));
        ResponseEntity<Map> created = post("/api/platform/tenants", platformToken(), body);
        assertThat(created.getStatusCode()).as("tạo tenant %s", code).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) created.getBody().get("id"));
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

    private ResponseEntity<Map> signup(String tenantCode, String username) {
        return post("/internal/auth/signup", null, Map.of("tenantCode", tenantCode, "username", username,
                "password", PASSWORD, "fullName", "Khách " + username, "email", username + "@khach.vn"));
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> storefrontLogin(String tenantCode, String username, String password) {
        return post("/internal/auth/login", null, Map.of("username", username, "password", password,
                "tenantCode", tenantCode, "channel", "STOREFRONT"));
    }

    /** Ghi đè hash OTP bằng OTP đã biết (cùng thuật toán và muối của dòng), vì thư không tới được hộp thư trong IT. */
    private void setKnownOtp(UUID userId, String otp) throws SQLException {
        String salt = query("SELECT otp_salt FROM email_verifications WHERE user_id = ?", String.class, userId);
        String hash = new HmacOtpCodec("").hash(salt, userId, otp);
        sql("UPDATE email_verifications SET otp_hash = ?, attempts = 0, expires_at = now() + interval '10 minutes', "
                + "consumed_at = NULL WHERE user_id = ?", hash, userId);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void luongDayDuTuDangKyDenDangNhap() throws Exception {
        createTenant("it-ev");

        // tự đăng ký: 201 dù SMTP không chạy; tài khoản chưa xác minh, chỉ có hash OTP
        assertThat(signup("it-ev", "khach1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID userId = query("SELECT user_id FROM credentials WHERE username = 'khach1'", UUID.class);
        assertThat(query("SELECT email_verified_at FROM credentials WHERE user_id = ?", java.sql.Timestamp.class, userId))
                .isNull();
        assertThat(query("SELECT otp_hash FROM email_verifications WHERE user_id = ?", String.class, userId))
                .matches("[0-9a-f]{64}");
        assertThat(query("SELECT count(*) FROM email_verifications WHERE user_id = ? AND consumed_at IS NULL",
                Long.class, userId)).isEqualTo(1L);

        // chưa xác minh: đúng mật khẩu bị chặn bằng mã riêng, sai mật khẩu vẫn là mã cũ (không lộ trạng thái)
        ResponseEntity<Map> blocked = storefrontLogin("it-ev", "khach1", PASSWORD);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody().get("code")).isEqualTo("EMAIL_NOT_VERIFIED");
        assertThat(storefrontLogin("it-ev", "khach1", "Sai@12345678").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // OTP sai: một mã chung, tính vào số lần thử; OTP lạ/tài khoản không tồn tại cùng mã
        setKnownOtp(userId, "123456");
        ResponseEntity<Map> wrong = post(CONFIRM, null, Map.of("username", "khach1", "otp", "000000"));
        ResponseEntity<Map> ghost = post(CONFIRM, null, Map.of("username", "khong-co", "otp", "000000"));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ghost.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrong.getBody().get("code")).isEqualTo(ghost.getBody().get("code"));
        assertThat(wrong.getBody().get("message")).isEqualTo(ghost.getBody().get("message"));
        assertThat(wrong.getBody().get("code")).isEqualTo("EMAIL_VERIFICATION_FAILED");
        assertThat(query("SELECT attempts FROM email_verifications WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);

        // OTP đúng (bằng email): xác minh xong, đăng nhập được, dùng lại thì thất bại
        assertThat(post(CONFIRM, null, Map.of("email", "khach1@khach.vn", "otp", "123456")).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(query("SELECT email_verified_at FROM credentials WHERE user_id = ?", java.sql.Timestamp.class, userId))
                .isNotNull();
        ResponseEntity<Map> ok = storefrontLogin("it-ev", "khach1", PASSWORD);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("accessToken")).isNotNull();
        assertThat(post(CONFIRM, null, Map.of("username", "khach1", "otp", "123456")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void quaSoLanThuSaiThiOtpDungCungBiVoHieuChoToiKhiGuiLai() throws Exception {
        createTenant("it-ev2");
        signup("it-ev2", "khach2");
        UUID userId = query("SELECT user_id FROM credentials WHERE username = 'khach2'", UUID.class);
        setKnownOtp(userId, "123456");

        for (int i = 0; i < 3; i++) {
            assertThat(post(CONFIRM, null, Map.of("username", "khach2", "otp", "000000")).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(post(CONFIRM, null, Map.of("username", "khach2", "otp", "123456")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(query("SELECT email_verified_at FROM credentials WHERE user_id = ?", java.sql.Timestamp.class, userId))
                .isNull();
        assertThat(query("SELECT attempts FROM email_verifications WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(3);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void otpHetHanBiTuChoi() throws Exception {
        createTenant("it-ev3");
        signup("it-ev3", "khach3");
        UUID userId = query("SELECT user_id FROM credentials WHERE username = 'khach3'", UUID.class);
        setKnownOtp(userId, "123456");
        sql("UPDATE email_verifications SET expires_at = now() - interval '1 second' WHERE user_id = ?", userId);

        assertThat(post(CONFIRM, null, Map.of("username", "khach3", "otp", "123456")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void resendLuon202GianCachVaKhongLoTaiKhoan() throws Exception {
        createTenant("it-ev4");
        signup("it-ev4", "khach4");
        UUID userId = query("SELECT user_id FROM credentials WHERE username = 'khach4'", UUID.class);

        assertThat(post(RESEND, null, Map.of("username", "khong-co")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post(RESEND, null, Map.of("email", "khong-co@khach.vn")).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        // vừa gửi lúc đăng ký: trong giãn cách 60 giây nên không sinh OTP mới (bộ đếm vẫn 1)
        assertThat(post(RESEND, null, Map.of("username", "khach4")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(query("SELECT sends_in_window FROM email_verifications WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);

        // quá giãn cách: sinh OTP mới, bộ đếm tăng, số lần thử về 0
        sql("UPDATE email_verifications SET last_sent_at = now() - interval '2 minutes', attempts = 2 WHERE user_id = ?",
                userId);
        assertThat(post(RESEND, null, Map.of("email", "khach4@khach.vn")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(query("SELECT sends_in_window FROM email_verifications WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(2);
        assertThat(query("SELECT attempts FROM email_verifications WHERE user_id = ?", Integer.class, userId)).isZero();

        // chạm trần 5 lần/ngày: bỏ qua yên lặng
        sql("UPDATE email_verifications SET sends_in_window = 5, last_sent_at = now() - interval '2 minutes' "
                + "WHERE user_id = ?", userId);
        assertThat(post(RESEND, null, Map.of("username", "khach4")).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(query("SELECT sends_in_window FROM email_verifications WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(5);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void taiKhoanCuVaNhanVienKhongBiKhoa() throws Exception {
        createTenant("it-ev5");

        // admin tenant (tạo bằng đường khác tự đăng ký) có email_verified_at khác NULL từ mặc định DB
        assertThat(query("SELECT email_verified_at FROM credentials WHERE username = 'it-ev5-admin'",
                java.sql.Timestamp.class)).isNotNull();
        ResponseEntity<Map> staff = post("/internal/auth/login", null,
                Map.of("username", "platform-root", "password", "PlatformRoot@123"));
        assertThat(staff.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
