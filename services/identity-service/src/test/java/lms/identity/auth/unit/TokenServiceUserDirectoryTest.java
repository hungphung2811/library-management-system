package lms.identity.auth.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.application.UserDirectoryPort;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.UserCredential;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Người dùng bị vô hiệu hóa không nhận được token; phòng ban trong token lấy từ hồ sơ người dùng. */
class TokenServiceUserDirectoryTest {

    final UUID userId = UUID.randomUUID();
    final UUID credentialDept = UUID.randomUUID();
    final UserCredential credential = UserCredential.register(userId, "alice", "h", credentialDept);
    final List<TokenIssuerPort.Claims> issued = new ArrayList<>();
    Optional<UserDirectoryPort.UserInfo> info = Optional.empty();

    TokenService service() {
        TokenIssuerPort issuer = c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); };
        UserAccessPort access = id -> new UserAccessPort.UserAccess(id, List.of("MEMBER"), List.of("k"), false);
        RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
            public String generate() { return "raw"; }
            public String hash(String raw) { return "h-" + raw; }
        };
        SaveRefreshTokenPort refresh = new SaveRefreshTokenPort() {
            public RefreshToken save(RefreshToken t) { return t; }
            public void revokeAllByUserId(UUID u) { }
        };
        return new TokenService(access, issuer, codec, refresh, Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofDays(7), id -> info, new TenantAccessStub());
    }

    @Test
    void nguoiDungBiVoHieuHoaKhongNhanDuocToken() {
        info = Optional.of(new UserDirectoryPort.UserInfo(true, null));
        assertThrows(AccountDisabledException.class, () -> service().issueFor(credential));
        assertThrows(AccountDisabledException.class, () -> service().issueForExternal(credential));
        assertEquals(0, issued.size());
    }

    @Test
    void khongCoHoSoNguoiDungThiDungThongTinCredential() {
        service().issueFor(credential);
        assertEquals(credentialDept, issued.get(0).departmentId());
    }

    @Test
    void phongBanTrongTokenLayTuHoSoNguoiDungNeuCo() {
        UUID dept = UUID.randomUUID();
        info = Optional.of(new UserDirectoryPort.UserInfo(false, dept));
        service().issueFor(credential);
        assertEquals(dept, issued.get(0).departmentId());

        info = Optional.of(new UserDirectoryPort.UserInfo(false, null)); // user chưa gán phòng ban: giữ của credential
        service().issueFor(credential);
        assertEquals(credentialDept, issued.get(1).departmentId());
    }
}
