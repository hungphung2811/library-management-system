package lms.identity.auth.infrastructure.security;

import lms.identity.auth.application.TokenIssuerPort;
import lms.security.JwtClaims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Phát hành access token RS256 chứa roles, permissions, departmentId, tenantId và platformAdmin (khi có). */
public class JwtTokenIssuer implements TokenIssuerPort {

    private final JwtEncoder encoder;
    private final String keyId;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;

    public JwtTokenIssuer(JwtEncoder encoder, String keyId, String issuer, Duration ttl, Clock clock) {
        this.encoder = encoder;
        this.keyId = keyId;
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(Claims claims) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(claims.userId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(JwtClaims.USERNAME, claims.username())
                .claim(JwtClaims.ROLES, claims.roles())
                .claim(JwtClaims.PERMISSIONS, claims.permissions());
        if (claims.mustChangePassword()) {
            builder.claim(JwtClaims.MUST_CHANGE_PASSWORD, true);
        }
        // Chỉ ghi claim khi true; token phải-đổi-mật-khẩu không bao giờ là admin dù người dùng có role quản trị
        if (claims.admin() && !claims.mustChangePassword()) {
            builder.claim(JwtClaims.ADMIN, true);
        }
        // Cũng như admin: token phải-đổi-mật-khẩu không mang quyền quản trị nền tảng
        if (claims.platformAdmin() && !claims.mustChangePassword()) {
            builder.claim(JwtClaims.PLATFORM_ADMIN, true);
        }
        if (claims.tenantId() != null) {
            builder.claim(JwtClaims.TENANT_ID, claims.tenantId().toString());
        }
        if (claims.departmentId() != null) {
            builder.claim(JwtClaims.DEPARTMENT_ID, claims.departmentId().toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
        return new IssuedToken(token, ttl.toSeconds());
    }
}
