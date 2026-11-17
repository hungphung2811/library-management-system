package lms.identity.auth.infrastructure.security;

import lms.identity.auth.application.ServiceTokenIssuerPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.security.JwtClaims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Phát hành access token RS256 chứa roles, permissions, departmentId, tenantId và platformAdmin (khi có). */
public class JwtTokenIssuer implements TokenIssuerPort, ServiceTokenIssuerPort {

    private final JwtEncoder encoder;
    private final String keyId;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;
    private final boolean staffAudience;

    public JwtTokenIssuer(JwtEncoder encoder, String keyId, String issuer, Duration ttl, Clock clock) {
        this(encoder, keyId, issuer, ttl, clock, false);
    }

    /**
     * staffAudience: ghi {@code aud = lms-staff} vào token kênh nhân viên (lms.identity.token.staff-audience). Mặc
     * định tắt để token cũ/mới không có aud vẫn dùng được trong giai đoạn chuyển tiếp; token kênh storefront luôn
     * mang {@code aud = lms-storefront} bất kể cờ này.
     */
    public JwtTokenIssuer(JwtEncoder encoder, String keyId, String issuer, Duration ttl, Clock clock,
                          boolean staffAudience) {
        this.staffAudience = staffAudience;
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
        boolean storefront = JwtClaims.AUDIENCE_STOREFRONT.equals(claims.audience());
        if (storefront) {
            builder.audience(List.of(JwtClaims.AUDIENCE_STOREFRONT));
        } else if (staffAudience) {
            builder.audience(List.of(JwtClaims.AUDIENCE_STAFF));
        }
        // Token kênh storefront KHÔNG BAO GIỜ mang admin/platformAdmin (hàng rào thứ hai; TokenService đã loại trước)
        if (claims.admin() && !claims.mustChangePassword() && !storefront) {
            builder.claim(JwtClaims.ADMIN, true);
        }
        // Cũng như admin: token phải-đổi-mật-khẩu không mang quyền quản trị nền tảng
        if (claims.platformAdmin() && !claims.mustChangePassword() && !storefront) {
            builder.claim(JwtClaims.PLATFORM_ADMIN, true);
        }
        if (claims.tenantId() != null) {
            builder.claim(JwtClaims.TENANT_ID, claims.tenantId().toString());
            // Module của tenant (ADR-0009): chỉ token có tenantId; token phải-đổi-mật-khẩu vẫn mang (không phải đặc quyền)
            if (claims.modules() != null) {
                builder.claim(JwtClaims.MODULES, List.copyOf(claims.modules()));
            }
        }
        if (claims.departmentId() != null) {
            builder.claim(JwtClaims.DEPARTMENT_ID, claims.departmentId().toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
        return new IssuedToken(token, ttl.toSeconds());
    }

    /** Token dịch vụ: không roles, username, admin, platformAdmin; permissions luôn rỗng; ttl do người gọi chọn. */
    @Override
    public IssuedToken issueService(ServiceClaims claims) {
        Instant now = clock.instant();
        JwtClaimsSet set = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(claims.subject().toString())
                .issuedAt(now)
                .expiresAt(now.plus(claims.ttl()))
                .claim(JwtClaims.SERVICE, claims.service())
                .claim(JwtClaims.TENANT_ID, claims.tenantId().toString())
                .claim(JwtClaims.MODULES, claims.modules())
                .claim(JwtClaims.PERMISSIONS, List.of())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, set)).getTokenValue();
        return new IssuedToken(token, claims.ttl().toSeconds());
    }
}
