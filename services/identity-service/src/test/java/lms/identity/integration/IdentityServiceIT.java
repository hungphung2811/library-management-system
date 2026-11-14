package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Khởi động nguyên identity-service (auth + accesscontrol) trên PostgreSQL thật và chạy luồng RBAC từ đầu đến cuối.
 * Kafka không chạy: broker giả không với tới được, listener tắt, producer chờ rất ngắn (chỉ log lỗi publish).
 * Cần Docker. Chạy: mvn test -Dtest=IdentityServiceIT -Dsurefire.failIfNoSpecifiedTests=false
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.producer.properties.max.block.ms=300",
        "spring.kafka.admin.properties.request.timeout.ms=500",
        "spring.kafka.admin.properties.default.api.timeout.ms=500",
        "auth.bootstrap-admin.password=Admin@12345"
})
@Testcontainers
class IdentityServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired TestRestTemplate rest;
    @Autowired JwtDecoder jwtDecoder;

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String username, String password, HttpStatus expected) {
        ResponseEntity<Map> response = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(response.getStatusCode()).as("login %s", username).isEqualTo(expected);
        return response.getBody();
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(url, method, new HttpEntity<>(body, headers), type);
    }

    @Test
    void adminDangNhapNhanTokenChuaRoleVaPermissionTuDatabase() {
        String token = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");

        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SUPER_ADMIN");
        assertThat(jwt.getClaimAsStringList("permissions")).contains("ROLE_ASSIGN", "USER_CREATE", "BORROW_BOOK");
        assertThat(jwt.getHeaders()).containsKey("kid");
    }

    @Test
    void jwksCongKhaiVaCoCungKid() {
        String token = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");
        String kid = (String) jwtDecoder.decode(token).getHeaders().get("kid");

        ResponseEntity<Map> jwks = rest.getForEntity("/.well-known/jwks.json", Map.class);

        assertThat(jwks.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> keys = (List<Map<String, Object>>) jwks.getBody().get("keys");
        assertThat(keys).extracting(k -> k.get("kid")).contains(kid);
        assertThat(keys.get(0)).doesNotContainKey("d");
    }

    @Test
    void apiPhanQuyenCanTokenVaPermission() {
        String adminToken = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");

        assertThat(call(HttpMethod.GET, "/api/roles", null, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/api/roles", "token-gia", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<List> roles = call(HttpMethod.GET, "/api/roles", adminToken, null, List.class);
        assertThat(roles.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roles.getBody()).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void taoNguoiDungGanRoleMemberRoiChiCoQuyenCuaMember() {
        String adminToken = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");
        UUID userId = UUID.randomUUID();

        assertThat(call(HttpMethod.POST, "/api/auth/credentials", adminToken,
                Map.of("userId", userId, "username", "member1", "password", "Member@1234"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> roles = call(HttpMethod.GET, "/api/roles", adminToken, null, List.class).getBody();
        String memberRoleId = (String) roles.stream().filter(r -> "MEMBER".equals(r.get("name"))).findFirst()
                .orElseThrow().get("id");
        assertThat(call(HttpMethod.POST, "/api/access/users/" + userId + "/roles", adminToken,
                Map.of("roleId", memberRoleId), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String memberToken = (String) login("member1", "Member@1234", HttpStatus.OK).get("accessToken");
        Jwt jwt = jwtDecoder.decode(memberToken);

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("MEMBER");
        assertThat(jwt.getClaimAsStringList("permissions")).containsExactlyInAnyOrder("BOOK_VIEW", "BORROW_BOOK");
        assertThat(call(HttpMethod.GET, "/api/roles", memberToken, null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.POST, "/api/auth/credentials", memberToken,
                Map.of("userId", UUID.randomUUID(), "username", "x1234", "password", "Whatever@123"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void lamMoiPhienXoayVongVaDungLaiTokenCuThuHoiMoiPhien() {
        Map<String, Object> first = login("admin", "Admin@12345", HttpStatus.OK);
        String oldRefresh = (String) first.get("refreshToken");

        ResponseEntity<Map> rotated = rest.postForEntity("/api/auth/refresh", Map.of("refreshToken", oldRefresh), Map.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefresh = (String) rotated.getBody().get("refreshToken");
        assertThat(newRefresh).isNotEqualTo(oldRefresh);

        // dùng lại token cũ: bị từ chối và mọi phiên của admin bị thu hồi, kể cả token mới
        assertThat(rest.postForEntity("/api/auth/refresh", Map.of("refreshToken", oldRefresh), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.postForEntity("/api/auth/refresh", Map.of("refreshToken", newRefresh), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void khoaTaiKhoanSauNamLanSaiMatKhauVaLuuDuocTrongDatabase() {
        String adminToken = (String) login("admin", "Admin@12345", HttpStatus.OK).get("accessToken");
        call(HttpMethod.POST, "/api/auth/credentials", adminToken,
                Map.of("userId", UUID.randomUUID(), "username", "locky", "password", "Correct@123"), Map.class);

        for (int i = 0; i < 5; i++) login("locky", "sai-mat-khau", HttpStatus.UNAUTHORIZED);

        // số lần sai phải được lưu dù ném exception (noRollbackFor); lần thứ sáu dù đúng mật khẩu vẫn bị khóa
        login("locky", "Correct@123", HttpStatus.LOCKED);
    }
}
