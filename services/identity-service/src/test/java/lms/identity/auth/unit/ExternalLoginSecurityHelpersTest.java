package lms.identity.auth.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.infrastructure.security.ExternalState;
import lms.identity.auth.infrastructure.security.Pkce;
import lms.identity.auth.infrastructure.security.ReturnUrlValidator;
import lms.identity.auth.infrastructure.security.StateCookieCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalLoginSecurityHelpersTest {

    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    final StateCookieCodec codec = new StateCookieCodec(new ObjectMapper(), "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    ExternalState state(long expiresAt) {
        return new ExternalState("google", "st", "no", "ver", "http://localhost:3000/cb", ExternalState.Mode.LINK,
                UUID.randomUUID(), expiresAt);
    }

    @Test
    void cookieTrangThaiMaHoaRoiGiaiMaDuocTrongHan() {
        ExternalState s = state(NOW.plusSeconds(600).getEpochSecond());
        assertEquals(s, codec.decode(codec.encode(s), NOW).orElseThrow());
    }

    @Test
    void cookieBiSuaGiaKhoaKhacHoacHetHanThiBiTuChoi() {
        String value = codec.encode(state(NOW.plusSeconds(600).getEpochSecond()));
        String tampered = "A" + value.substring(1);
        assertTrue(codec.decode(tampered, NOW).isEmpty());
        assertTrue(codec.decode(value.substring(0, value.lastIndexOf('.') + 1) + "AAAA", NOW).isEmpty());
        StateCookieCodec other = new StateCookieCodec(new ObjectMapper(), "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));
        assertTrue(other.decode(value, NOW).isEmpty());
        assertTrue(codec.decode(value, NOW.plusSeconds(601)).isEmpty());
        assertTrue(codec.decode(null, NOW).isEmpty());
        assertTrue(codec.decode("khong-co-dau-cham", NOW).isEmpty());
    }

    @Test
    void returnUrlChiChoOriginDaKhaiBao() {
        ReturnUrlValidator v = new ReturnUrlValidator(List.of("http://localhost:3000", "https://lib.school.test"));
        assertTrue(v.isAllowed("http://localhost:3000/"));
        assertTrue(v.isAllowed("http://localhost:3000/dang-nhap?x=1"));
        assertTrue(v.isAllowed("https://LIB.school.test/app"));
        for (String bad : new String[]{null, "", "  ", "//evil.example", "javascript:alert(1)", "https://evil.example",
                "http://localhost:3000.evil.com/", "http://localhost:3001/", "http://user:pw@localhost:3000/",
                "http://localhost:3000/#frag", "/relative", "ftp://localhost:3000/", "https://lib.school.test:8443/"}) {
            assertFalse(v.isAllowed(bad), "phải từ chối: " + bad);
        }
        assertThrows(ExternalAuthException.class, () -> v.validate("https://evil.example"));
        assertFalse(new ReturnUrlValidator(List.of()).isAllowed("http://localhost:3000/"));
    }

    @Test
    void pkceChallengeTheoRfc7636() {
        // Ví dụ trong RFC 7636, Appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertTrue(Pkce.randomToken(32).length() >= 43);
    }
}
