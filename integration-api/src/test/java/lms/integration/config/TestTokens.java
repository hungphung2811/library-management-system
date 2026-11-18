package lms.integration.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Khóa RS256 thử nghiệm: phát token giả lập identity và dựng JwtDecoder giống cấu hình thật (chỉ khác nguồn khóa). */
public final class TestTokens {

    public static final String ISSUER = "library-auth-service";

    private final RSAKey key;
    private final NimbusJwtEncoder encoder;

    public TestTokens() {
        this(generate());
    }

    private TestTokens(RSAKey key) {
        this.key = key;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-kid").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Decoder dùng đúng validator của {@link IntegrationJwtConfiguration}. */
    public JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
            decoder.setJwtValidator(IntegrationJwtConfiguration.validator(ISSUER));
            return decoder;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dựng token; {@code audience} null = không có aud. */
    public String sign(String issuer, List<String> audience, Duration ttlFromNow, java.util.Map<String, Object> extra,
                       TestTokens signer) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer(issuer).subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString()).issuedAt(now.minus(Duration.ofMinutes(10)))
                .expiresAt(now.plus(ttlFromNow));
        if (audience != null) b.audience(audience);
        extra.forEach(b::claim);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-kid").build();
        return signer.encoder.encode(JwtEncoderParameters.from(header, b.build())).getTokenValue();
    }

    public String valid() {
        return sign(ISSUER, List.of("lms-integration"), Duration.ofMinutes(5), claims(), this);
    }

    public String with(String issuer, List<String> audience, Duration ttl, java.util.Map<String, Object> extra) {
        return sign(issuer, audience, ttl, extra, this);
    }

    public static java.util.Map<String, Object> claims() {
        java.util.Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("tenantId", UUID.randomUUID().toString());
        claims.put("application_id", UUID.randomUUID().toString());
        claims.put("scope", "books:read");
        claims.put("modules", new ArrayList<>(List.of("SALES")));
        return claims;
    }
}
