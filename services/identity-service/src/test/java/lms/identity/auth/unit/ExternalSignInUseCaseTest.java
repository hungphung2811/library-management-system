package lms.identity.auth.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.auth.application.ExchangeExternalTicketUseCase;
import lms.identity.auth.application.ExternalIdentity;
import lms.identity.auth.application.ExternalSignInUseCase;
import lms.identity.auth.application.ExternalTicketPort;
import lms.identity.auth.application.LinkExternalLoginUseCase;
import lms.identity.auth.application.ReaderLookupPort;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UnlinkExternalLoginUseCase;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.domain.ExternalLogin;
import lms.identity.auth.domain.ExternalLoginRepository;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Duration;
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

/** Quy tắc đăng nhập ngoài (không cần Spring/DB). */
class ExternalSignInUseCaseTest {

    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    final UUID alice = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final Map<UUID, UserCredential> credentialStore = new HashMap<>();
    final List<ExternalLogin> loginStore = new ArrayList<>();
    final Map<String, List<UUID>> readersByEmail = new HashMap<>();
    final Map<String, UUID> tickets = new HashMap<>();
    List<String> allowedDomains = List.of("school.test");

    final UserCredentialRepository credentials = new UserCredentialRepository() {
        public Optional<UserCredential> findByUsername(String u) { return Optional.empty(); }
        public Optional<UserCredential> findByUserId(UUID id) { return Optional.ofNullable(credentialStore.get(id)); }
        public boolean existsByUserId(UUID id) { return credentialStore.containsKey(id); }
        public boolean existsByUsername(String u) { return false; }
    };
    final ExternalLoginRepository logins = new ExternalLoginRepository() {
        public ExternalLogin save(ExternalLogin l) { loginStore.removeIf(x -> x.id().equals(l.id())); loginStore.add(l); return l; }
        public Optional<ExternalLogin> findByProviderAndSubject(String p, String s) {
            return loginStore.stream().filter(l -> l.provider().equals(p) && l.subject().equals(s)).findFirst();
        }
        public Optional<ExternalLogin> findByUserIdAndProvider(UUID u, String p) {
            return loginStore.stream().filter(l -> l.userId().equals(u) && l.provider().equals(p)).findFirst();
        }
        public List<ExternalLogin> findByUserId(UUID u) { return loginStore.stream().filter(l -> l.userId().equals(u)).toList(); }
        public void delete(ExternalLogin l) { loginStore.removeIf(x -> x.id().equals(l.id())); }
    };
    final ReaderLookupPort readers = email -> readersByEmail.getOrDefault(email, List.of());
    final ExternalTicketPort ticketPort = new ExternalTicketPort() {
        public String issue(UUID userId, Instant now) { String code = "code-" + tickets.size(); tickets.put(code, userId); return code; }
        public Optional<UUID> redeem(String code, Instant now) { return Optional.ofNullable(tickets.remove(code)); }
    };
    ExternalSignInUseCase signIn;
    LinkExternalLoginUseCase link;

    @BeforeEach
    void setUp() {
        credentialStore.put(alice, UserCredential.register(alice, "alice", "h", null));
        credentialStore.put(bob, UserCredential.register(bob, "bob", "h", null));
        readersByEmail.put("alice@school.test", List.of(alice));
        signIn = new ExternalSignInUseCase(logins, credentials, readers,
                (provider, email) -> allowedDomains.isEmpty() || allowedDomains.contains(email.substring(email.indexOf('@') + 1)),
                ticketPort, CLOCK);
        link = new LinkExternalLoginUseCase(logins, credentials, CLOCK);
    }

    static ExternalIdentity id(String sub, String email, boolean verified) {
        return new ExternalIdentity("google", sub, email, verified);
    }

    private static Reason reason(Runnable r) {
        return assertThrows(ExternalAuthException.class, r::run).reason();
    }

    @Test
    void lanDauTuLienKetKhiEmailDaXacMinhDungTenMienVaCoDungMotDocGia() {
        String code = signIn.execute(id("sub-1", "alice@school.test", true));
        assertEquals(alice, tickets.get(code));
        assertEquals(1, loginStore.size());
        assertEquals("sub-1", loginStore.get(0).subject());
    }

    @Test
    void tuChoiKhiEmailChuaXacMinhSaiTenMienKhongCoHoacNhieuDocGia() {
        assertEquals(Reason.ACCOUNT_NOT_LINKED, reason(() -> signIn.execute(id("s", "alice@school.test", false))));
        assertEquals(Reason.ACCOUNT_NOT_LINKED, reason(() -> signIn.execute(id("s", "alice@evil.test", true))));
        assertEquals(Reason.ACCOUNT_NOT_LINKED, reason(() -> signIn.execute(id("s", "nobody@school.test", true))));
        assertEquals(Reason.ACCOUNT_NOT_LINKED, reason(() -> signIn.execute(id("s", null, true))));
        readersByEmail.put("dup@school.test", List.of(alice, bob));
        assertEquals(Reason.ACCOUNT_NOT_LINKED, reason(() -> signIn.execute(id("s", "dup@school.test", true))));
        assertTrue(loginStore.isEmpty());
        assertTrue(tickets.isEmpty());
    }

    @Test
    void khongGioiHanTenMienKhiDanhSachRong() {
        allowedDomains = List.of();
        readersByEmail.put("alice@gmail.com", List.of(alice));
        assertTrue(signIn.execute(id("s", "alice@gmail.com", true)).startsWith("code-"));
    }

    @Test
    void daLienKetThiDangNhapDungNguoiKeCaKhiEmailDoiHoacChuaXacMinh() {
        signIn.execute(id("sub-1", "alice@school.test", true));
        tickets.clear();

        String code = signIn.execute(id("sub-1", "other@gmail.com", false));
        assertEquals(alice, tickets.get(code));
        assertEquals("other@gmail.com", loginStore.get(0).email());
        assertEquals(1, loginStore.size());
    }

    @Test
    void taiKhoanBiVoHieuHoaBiChanVaKhongTaoLienKet() {
        credentialStore.get(alice).deactivate();
        assertThrows(AccountDisabledException.class, () -> signIn.execute(id("sub-1", "alice@school.test", true)));
        assertTrue(loginStore.isEmpty());
    }

    @Test
    void khongTuLienKetKhiNguoiDungDaCoLienKetKhacCungNhaCungCap() {
        signIn.execute(id("sub-1", "alice@school.test", true));
        assertEquals(Reason.ACCOUNT_NOT_LINKED, reason(() -> signIn.execute(id("sub-2", "alice@school.test", true))));
        assertEquals(1, loginStore.size());
    }

    @Test
    void lienKetChuDongVaGoLienKet() {
        link.execute(bob, id("sub-b", "bob@gmail.com", true));
        assertEquals(1, loginStore.size());
        link.execute(bob, id("sub-b", "bob@gmail.com", true)); // idempotent
        assertEquals(1, loginStore.size());
        assertEquals(Reason.ALREADY_LINKED, reason(() -> link.execute(alice, id("sub-b", "x@gmail.com", true))));
        assertEquals(Reason.ALREADY_LINKED, reason(() -> link.execute(bob, id("sub-c", "bob@gmail.com", true))));

        UnlinkExternalLoginUseCase unlink = new UnlinkExternalLoginUseCase(logins);
        assertEquals(Reason.EXTERNAL_LOGIN_NOT_FOUND, reason(() -> unlink.execute(alice, "google")));
        unlink.execute(bob, "google");
        assertTrue(loginStore.isEmpty());
    }

    @Test
    void doiMaLayTokenMotLanVaDangNhapNgoaiKhongBiRangBuocDoiMatKhau() {
        credentialStore.put(alice, UserCredential.register(alice, "alice", "h", null, true)); // phải đổi mật khẩu
        List<TokenIssuerPort.Claims> issued = new ArrayList<>();
        TokenIssuerPort issuer = claims -> { issued.add(claims); return new TokenIssuerPort.IssuedToken("jwt", 900); };
        UserAccessPort access = u -> new UserAccessPort.UserAccess(u, List.of("MEMBER"), List.of("BOOK_VIEW"));
        RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
            public String generate() { return "raw"; }
            public String hash(String raw) { return "h-" + raw; }
        };
        SaveRefreshTokenPort refresh = new SaveRefreshTokenPort() {
            public RefreshToken save(RefreshToken t) { return t; }
            public void revokeAllByUserId(UUID u) { }
        };
        ExchangeExternalTicketUseCase exchange = new ExchangeExternalTicketUseCase(ticketPort, credentials,
                new TokenService(access, issuer, codec, refresh, CLOCK, Duration.ofDays(7)), CLOCK);

        String code = signIn.execute(id("sub-1", "alice@school.test", true));
        var response = exchange.execute(code);

        assertFalse(response.mustChangePassword());
        assertEquals(List.of("BOOK_VIEW"), issued.get(0).permissions());
        assertEquals(Reason.INVALID_TICKET, reason(() -> exchange.execute(code))); // dùng lại bị từ chối
        assertEquals(Reason.INVALID_TICKET, reason(() -> exchange.execute("sai")));
    }
}
