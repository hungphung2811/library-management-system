package lms.identity.auth.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.auth.application.ConfirmPasswordResetUseCase;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.RequestPasswordResetUseCase;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.SavePasswordResetTokenPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.UserDirectoryPort;
import lms.identity.auth.domain.InvalidPasswordResetTokenException;
import lms.identity.auth.domain.PasswordResetRequestedEvent;
import lms.identity.auth.domain.PasswordResetToken;
import lms.identity.auth.domain.PasswordResetTokenRepository;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.tenant.application.TransactionalRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Quên mật khẩu (yêu cầu + xác nhận) và mở khóa: dùng bản giả trong bộ nhớ, không cần Spring/DB. */
class PasswordResetTest {

    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final UUID userId = UUID.randomUUID();

    final PasswordHasherPort hasher = new PasswordHasherPort() {
        public String hash(String raw) { return "h:" + raw; }
        public boolean matches(String raw, String hash) { return hash.equals("h:" + raw); }
    };
    final RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
        int n;
        public String generate() { return "raw-" + (++n); }
        public String hash(String raw) { return "sha:" + raw; }
    };

    final Map<UUID, UserCredential> credentials = new HashMap<>();
    final Map<String, PasswordResetToken> tokensByHash = new HashMap<>();
    final List<PasswordResetToken> saved = new ArrayList<>();
    final List<PasswordResetRequestedEvent> published = new ArrayList<>();
    final List<UUID> revokedUsers = new ArrayList<>();
    boolean disabledProfile;

    final UserCredentialRepository credentialRepo = new UserCredentialRepository() {
        public Optional<UserCredential> findByUsername(String u) {
            return credentials.values().stream().filter(c -> c.username().equals(u)).findFirst();
        }
        public Optional<UserCredential> findByUserId(UUID id) { return Optional.ofNullable(credentials.get(id)); }
        public boolean existsByUserId(UUID id) { return credentials.containsKey(id); }
        public boolean existsByUsername(String u) { return findByUsername(u).isPresent(); }
    };
    final PasswordResetTokenRepository tokenRepo = new PasswordResetTokenRepository() {
        public Optional<PasswordResetToken> findByTokenHash(String h) { return Optional.ofNullable(tokensByHash.get(h)); }
        public long countCreatedSince(UUID id, Instant since) {
            return saved.stream().filter(t -> t.userId().equals(id) && !t.createdAt().isBefore(since)).count();
        }
    };
    final Map<UUID, Instant> usedAt = new HashMap<>();
    final SavePasswordResetTokenPort tokenSave = new SavePasswordResetTokenPort() {
        public PasswordResetToken save(PasswordResetToken t) {
            saved.add(t);
            tokensByHash.put(t.tokenHash(), t);
            return t;
        }
        public void invalidateOutstanding(UUID id, Instant now) {
            saved.stream().filter(t -> t.userId().equals(id)).forEach(t -> usedAt.putIfAbsent(t.id(), now));
        }
        public boolean consume(UUID tokenId, Instant now) { return usedAt.putIfAbsent(tokenId, now) == null; }
    };
    final UserDirectoryPort directory = id -> disabledProfile
            ? Optional.of(new UserDirectoryPort.UserInfo(true)) : Optional.empty();
    final SaveCredentialPort credentialSave = c -> { credentials.put(c.userId(), c); return c; };
    final SaveRefreshTokenPort refreshSave = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { return t; }
        public void revokeAllByUserId(UUID id) { revokedUsers.add(id); }
    };

    RequestPasswordResetUseCase request;
    ConfirmPasswordResetUseCase confirm;

    @BeforeEach
    void setUp() {
        credentials.put(userId, UserCredential.register(userId, "alice", "h:old", null, true));
        request = new RequestPasswordResetUseCase(credentialRepo, tokenRepo, tokenSave, codec, published::add,
                directory, new TransactionalRunner(), clock);
        confirm = new ConfirmPasswordResetUseCase(tokenRepo, tokenSave, credentialRepo, credentialSave, refreshSave,
                hasher, codec, directory, clock);
    }

    @Test
    void yeuCauThanhCongLuuHashPhatEventVaKhongLuuTokenTho() {
        request.execute("  Alice ");

        assertEquals(1, saved.size());
        assertEquals("sha:raw-1", saved.get(0).tokenHash());
        assertEquals(NOW.plus(RequestPasswordResetUseCase.TOKEN_TTL), saved.get(0).expiresAt());
        assertEquals(1, published.size());
        assertEquals("raw-1", published.get(0).token());
        assertEquals(userId, published.get(0).userId());
        assertFalse(published.get(0).toString().contains("raw-1"));
    }

    @Test
    void taiKhoanKhongTonTaiHoacBiVoHieuKhongPhatSinhGiVaKhongNem() {
        request.execute("khong-co");
        credentials.put(userId, UserCredential.restore(userId, "alice", "h:old", null, false, 0, null));
        request.execute("alice");
        credentials.put(userId, UserCredential.register(userId, "alice", "h:old", null));
        disabledProfile = true;
        request.execute("alice");

        assertTrue(saved.isEmpty());
        assertTrue(published.isEmpty());
    }

    @Test
    void vuotGioiHanTanSuatThiBoQuaYenLang() {
        for (int i = 0; i < RequestPasswordResetUseCase.MAX_REQUESTS_PER_WINDOW + 2; i++) request.execute("alice");

        assertEquals(RequestPasswordResetUseCase.MAX_REQUESTS_PER_WINDOW, saved.size());
        assertEquals(RequestPasswordResetUseCase.MAX_REQUESTS_PER_WINDOW, published.size());
    }

    @Test
    void xacNhanDoiMatKhauBoCoMustChangeVaThuHoiPhien() {
        request.execute("alice");
        // yêu cầu thứ hai vô hiệu token đầu: chỉ token mới nhất dùng được
        request.execute("alice");
        assertThrows(InvalidPasswordResetTokenException.class, () -> confirm.execute("raw-1", "MatKhauMoi1"));

        confirm.execute("raw-2", "MatKhauMoi1");

        UserCredential after = credentials.get(userId);
        assertEquals("h:MatKhauMoi1", after.passwordHash());
        assertFalse(after.mustChangePassword());
        assertTrue(revokedUsers.contains(userId));
    }

    @Test
    void tokenDungMotLan() {
        request.execute("alice");
        confirm.execute("raw-1", "MatKhauMoi1");

        assertThrows(InvalidPasswordResetTokenException.class, () -> confirm.execute("raw-1", "MatKhauKhac2"));
    }

    @Test
    void tokenSaiHoacHetHanBiTuChoiCungMotThongDiep() {
        PasswordResetToken expired = PasswordResetToken.restore(UUID.randomUUID(), userId, "sha:old",
                NOW.minusSeconds(1), NOW.minusSeconds(1800), null);
        tokensByHash.put("sha:old", expired);

        InvalidPasswordResetTokenException wrong = assertThrows(InvalidPasswordResetTokenException.class,
                () -> confirm.execute("khong-co", "MatKhauMoi1"));
        InvalidPasswordResetTokenException old = assertThrows(InvalidPasswordResetTokenException.class,
                () -> confirm.execute("old", "MatKhauMoi1"));
        assertEquals(wrong.getMessage(), old.getMessage());
    }

    @Test
    void matKhauYeuBiTuChoiVaKhongTieuThuToken() {
        request.execute("alice");

        assertThrows(IllegalArgumentException.class, () -> confirm.execute("raw-1", "ngan"));
        // token vẫn dùng được sau khi mật khẩu yếu bị từ chối
        confirm.execute("raw-1", "MatKhauMoi1");
        assertNotNull(credentials.get(userId));
    }

    @Test
    void moKhoaXoaFailedAttemptsVaLockedUntil() {
        UserCredential c = UserCredential.restore(userId, "alice", "h:old", null, true, 3, NOW.plusSeconds(600));
        assertTrue(c.isLocked(NOW));

        c.unlock(UUID.randomUUID(), NOW);

        assertFalse(c.isLocked(NOW));
        assertNull(c.lockedUntil());
        assertEquals(0, c.failedAttempts());
        assertEquals(1, c.pullEvents().size());
    }
}
