package lms.identity.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;

import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.infrastructure.persistence.AuthPersistenceAdapter;
import lms.identity.auth.infrastructure.persistence.CredentialJpaRepository;
import lms.identity.auth.infrastructure.persistence.RefreshTokenJpaRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Cần Docker. Chạy: mvn test -Dtest=AuthPersistenceAdapterIT. Kiểm chứng migration + ánh xạ JPA (ddl validate). */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration/identity"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AuthPersistenceAdapterIT.Config.class)
class AuthPersistenceAdapterIT {

    @TestConfiguration
    static class Config {
        @Bean
        AuthPersistenceAdapter adapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository tokens) {
            return new AuthPersistenceAdapter(credentials, tokens);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AuthPersistenceAdapter adapter;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void luuVaDocLaiCredentialKemTrangThaiKhoa() {
        UUID userId = UUID.randomUUID();
        UserCredential credential = UserCredential.register(userId, "alice", "bcrypt-hash", UUID.randomUUID());
        for (int i = 0; i < UserCredential.MAX_FAILED_ATTEMPTS; i++) credential.recordFailure(now);
        adapter.save(credential);

        UserCredential loaded = adapter.findByUsername("alice").orElseThrow();

        assertThat(loaded.userId()).isEqualTo(userId);
        assertThat(loaded.isLocked(now)).isTrue();
        assertThat(loaded.departmentId()).isEqualTo(credential.departmentId());
        assertThat(adapter.existsByUsername("alice")).isTrue();
        assertThat(adapter.existsByUserId(userId)).isTrue();
        assertThat(adapter.findByUsername("nobody")).isEmpty();
    }

    @Test
    void refreshTokenLuuTenantIdVaCoPhienNenTangCredentialLuuCoPlatformAdmin() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        adapter.save(RefreshToken.issueForTenant(user, tenant, "t-1", now, Duration.ofDays(7)));
        adapter.save(RefreshToken.issueForPlatform(user, "p-1", now, Duration.ofDays(7)));
        adapter.save(RefreshToken.issue(user, "legacy-1", now, Duration.ofDays(7)));

        assertThat(adapter.findByTokenHash("t-1").orElseThrow())
                .extracting(RefreshToken::tenantId, RefreshToken::platformSession).containsExactly(tenant, false);
        assertThat(adapter.findByTokenHash("p-1").orElseThrow())
                .extracting(RefreshToken::tenantId, RefreshToken::platformSession).containsExactly(null, true);
        assertThat(adapter.findByTokenHash("legacy-1").orElseThrow())
                .extracting(RefreshToken::tenantId, RefreshToken::platformSession).containsExactly(null, false);

        UUID root = UUID.randomUUID();
        adapter.save(UserCredential.restore(root, "root-op", "bcrypt-hash", null, true, 0, null, false, true));
        assertThat(adapter.findByUserId(root).orElseThrow().platformAdmin()).isTrue();
        assertThat(adapter.findByUsername("alice-x")).isEmpty();
    }

    @Test
    void thuHoiTatCaRefreshTokenCuaNguoiDungChiAnhHuongNguoiDoMa() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        adapter.save(RefreshToken.issue(alice, "alice-1", now, Duration.ofDays(7)));
        adapter.save(RefreshToken.issue(alice, "alice-2", now, Duration.ofDays(7)));
        adapter.save(RefreshToken.issue(bob, "bob-1", now, Duration.ofDays(7)));

        adapter.revokeAllByUserId(alice);

        assertThat(adapter.findByTokenHash("alice-1").orElseThrow().isRevoked()).isTrue();
        assertThat(adapter.findByTokenHash("alice-2").orElseThrow().isRevoked()).isTrue();
        assertThat(adapter.findByTokenHash("bob-1").orElseThrow().isRevoked()).isFalse();
        assertThat(adapter.findByTokenHash("khong-co")).isEmpty();
    }
}
