package lms.identity.auth.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ChangePasswordUseCase;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.domain.WrongCurrentPasswordException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UC01 đổi mật khẩu và cờ mustChangePassword: không cần Spring/DB. */
class ChangePasswordTest {

    static final PasswordHasherPort HASHER = new PasswordHasherPort() {
        public String hash(String raw) { return "h:" + raw; }
        public boolean matches(String raw, String hash) { return hash.equals("h:" + raw); }
    };

    final UUID userId = UUID.randomUUID();
    UserCredential stored;
    final List<UUID> revoked = new ArrayList<>();

    final UserCredentialRepository repository = new UserCredentialRepository() {
        public Optional<UserCredential> findByUsername(String u) { return Optional.ofNullable(stored); }
        public Optional<UserCredential> findByUserId(UUID id) { return Optional.ofNullable(stored); }
        public boolean existsByUserId(UUID id) { return stored != null; }
        public boolean existsByUsername(String u) { return stored != null; }
    };
    final SaveCredentialPort savePort = c -> { stored = c; return c; };
    final SaveRefreshTokenPort refreshSave = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { return t; }
        public void revokeAllByUserId(UUID id) { revoked.add(id); }
    };
    ChangePasswordUseCase useCase;

    @BeforeEach
    void setUp() {
        stored = UserCredential.register(userId, "alice", "h:OldPass123", null, true);
        useCase = new ChangePasswordUseCase(repository, savePort, refreshSave, HASHER);
    }

    @Test
    void doiMatKhauThanhCongBoCoBatBuocVaThuHoiMoiPhien() {
        useCase.execute(userId, "OldPass123", "NewPass456");
        assertEquals("h:NewPass456", stored.passwordHash());
        assertFalse(stored.mustChangePassword());
        assertEquals(List.of(userId), revoked);
    }

    @Test
    void tuChoiSaiMatKhauHienTaiMatKhauMoiYeuHoacTrungMatKhauCu() {
        assertThrows(WrongCurrentPasswordException.class, () -> useCase.execute(userId, "sai", "NewPass456"));
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(userId, "OldPass123", "short"));
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(userId, "OldPass123", "OldPass123"));
        assertEquals("h:OldPass123", stored.passwordHash());
        assertTrue(stored.mustChangePassword());
        assertTrue(revoked.isEmpty());
    }

    @Test
    void tuChoiKhiKhongCoTaiKhoan() {
        stored = null;
        assertThrows(InvalidCredentialsException.class, () -> useCase.execute(userId, "a", "NewPass456"));
    }

    @Test
    void doiMatKhauXoaTrangThaiKhoa() {
        stored = UserCredential.restore(userId, "alice", "h:OldPass123", null, true, 3,
                Instant.parse("2030-01-01T00:00:00Z"), false);
        useCase.execute(userId, "OldPass123", "NewPass456");
        assertEquals(0, stored.failedAttempts());
        assertEquals(null, stored.lockedUntil());
    }

    @Test
    void tokenCuaTaiKhoanPhaiDoiMatKhauKhongMangPermissionNaoVaBaoCoChoClient() {
        List<TokenIssuerPort.Claims> issued = new ArrayList<>();
        TokenIssuerPort issuer = claims -> { issued.add(claims); return new TokenIssuerPort.IssuedToken("jwt", 900); };
        UserAccessPort access = id -> new UserAccessPort.UserAccess(id, List.of("MEMBER"), List.of("BOOK_VIEW", "BORROW_BOOK"));
        RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
            public String generate() { return "raw"; }
            public String hash(String raw) { return "hash-" + raw; }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
        TokenService tokens = new TokenService(access, issuer, codec, refreshSave, clock, Duration.ofDays(7),
                new TenantAccessStub());

        TokenResponse forced = tokens.issueFor(stored); // stored: mustChangePassword = true
        assertTrue(forced.mustChangePassword());
        assertTrue(issued.get(0).mustChangePassword());
        assertEquals(List.of(), issued.get(0).permissions());
        assertEquals(List.of("MEMBER"), issued.get(0).roles());

        useCase.execute(userId, "OldPass123", "NewPass456");
        TokenResponse normal = tokens.issueFor(stored);
        assertFalse(normal.mustChangePassword());
        assertEquals(List.of("BOOK_VIEW", "BORROW_BOOK"), issued.get(1).permissions());
    }
}
