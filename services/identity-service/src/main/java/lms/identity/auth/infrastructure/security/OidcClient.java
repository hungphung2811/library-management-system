package lms.identity.auth.infrastructure.security;

import lms.identity.auth.application.ExternalIdentity;
import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.infrastructure.config.ExternalAuthProperties.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Máy khách OIDC tối thiểu: Authorization Code + PKCE (S256), client_secret_post. Discovery theo authority (cache 1 giờ,
 * issuer phải trùng authority), id_token kiểm chữ ký bằng JWKS của nhà cung cấp, issuer, audience, hạn và nonce.
 */
public class OidcClient {

    public record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {
    }

    private record Cached(Discovery discovery, Instant fetchedAt) {
    }

    private static final Duration DISCOVERY_TTL = Duration.ofHours(1);

    private final RestClient http;
    private final Clock clock;
    private final Map<String, Cached> discoveries = new ConcurrentHashMap<>();
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    public OidcClient(RestClient http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText("");
        if (v.isBlank()) throw new IllegalStateException("Discovery thiếu " + field);
        return v;
    }

    Discovery discovery(Provider p) {
        String authority = strip(p.getAuthority());
        Cached cached = discoveries.get(authority);
        if (cached != null && cached.fetchedAt().plus(DISCOVERY_TTL).isAfter(clock.instant())) {
            return cached.discovery();
        }
        try {
            JsonNode n = http.get().uri(authority + "/.well-known/openid-configuration").retrieve().body(JsonNode.class);
            if (n == null) throw new IllegalStateException("Discovery rỗng");
            Discovery d = new Discovery(text(n, "issuer"), text(n, "authorization_endpoint"),
                    text(n, "token_endpoint"), text(n, "jwks_uri"));
            if (!strip(d.issuer()).equals(authority)) {
                throw new IllegalStateException("issuer " + d.issuer() + " không trùng authority " + authority);
            }
            discoveries.put(authority, new Cached(d, clock.instant()));
            return d;
        } catch (RestClientException | IllegalStateException e) {
            throw new ExternalAuthException(Reason.PROVIDER_UNAVAILABLE, "Không lấy được cấu hình của nhà cung cấp", e);
        }
    }

    public String authorizationUrl(Provider p, String redirectUri, String state, String nonce, String codeChallenge) {
        Discovery d = discovery(p);
        return UriComponentsBuilder.fromUriString(d.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", p.getClientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", p.getScopes())
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
    }

    public ExternalIdentity exchange(String providerName, Provider p, String code, String codeVerifier, String nonce,
                                     String redirectUri) {
        Discovery d = discovery(p);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", p.getClientId());
        if (p.getClientSecret() != null && !p.getClientSecret().isBlank()) {
            form.add("client_secret", p.getClientSecret());
        }
        form.add("code_verifier", codeVerifier);

        JsonNode token;
        try {
            token = http.post().uri(d.tokenEndpoint()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw new ExternalAuthException(Reason.EXCHANGE_FAILED, "Nhà cung cấp từ chối mã đăng nhập", e);
        } catch (RestClientException e) {
            throw new ExternalAuthException(Reason.PROVIDER_UNAVAILABLE, "Không gọi được nhà cung cấp", e);
        }
        String idToken = token == null ? "" : token.path("id_token").asText("");
        if (idToken.isBlank()) {
            throw new ExternalAuthException(Reason.EXCHANGE_FAILED, "Nhà cung cấp không trả id_token");
        }

        Jwt jwt;
        try {
            jwt = decoder(d, p).decode(idToken);
        } catch (Exception e) {
            throw new ExternalAuthException(Reason.EXCHANGE_FAILED, "id_token không hợp lệ", e);
        }
        if (!nonce.equals(jwt.getClaimAsString("nonce"))) {
            throw new ExternalAuthException(Reason.EXCHANGE_FAILED, "nonce không khớp");
        }
        Object verified = jwt.getClaim("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equalsIgnoreCase(String.valueOf(verified));
        return new ExternalIdentity(providerName, jwt.getSubject(), jwt.getClaimAsString("email"), emailVerified);
    }

    private JwtDecoder decoder(Discovery d, Provider p) {
        return decoders.computeIfAbsent(d.jwksUri() + "|" + p.getClientId(), key -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(d.jwksUri()).build();
            OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(p.getClientId())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience không khớp client_id", null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(d.issuer()), audience));
            return decoder;
        });
    }
}
