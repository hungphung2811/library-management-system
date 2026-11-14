package lms.identity.auth.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ExchangeExternalTicketUseCase;
import lms.identity.auth.application.ExternalIdentity;
import lms.identity.auth.application.ExternalLoginResponse;
import lms.identity.auth.application.ExternalSignInUseCase;
import lms.identity.auth.application.LinkExternalLoginUseCase;
import lms.identity.auth.application.ListExternalLoginsUseCase;
import lms.identity.auth.application.UnlinkExternalLoginUseCase;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.ExternalAuthException.Reason;
import lms.identity.auth.infrastructure.config.ExternalAuthProperties;
import lms.identity.auth.infrastructure.config.ExternalAuthProperties.Provider;
import lms.identity.auth.infrastructure.security.ExternalProviderRegistry;
import lms.identity.auth.infrastructure.security.ExternalProviderRegistry.ProviderInfo;
import lms.identity.auth.infrastructure.security.ExternalState;
import lms.identity.auth.infrastructure.security.OidcClient;
import lms.identity.auth.infrastructure.security.Pkce;
import lms.identity.auth.infrastructure.security.ReturnUrlValidator;
import lms.identity.auth.infrastructure.security.StateCookieCodec;
import lms.security.CurrentUser;
import lms.security.permission.AuthenticatedOnly;
import lms.security.permission.PublicEndpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Đăng nhập ngoài (OIDC Authorization Code + PKCE). Trình duyệt đi /external/{provider}/start, quay về
 * /external/{provider}/callback, rồi được chuyển về returnUrl của frontend kèm mã dùng một lần ở fragment
 * (#externalCode=...) hoặc lỗi ở query (?authError=...). Frontend đổi mã lấy token bằng POST /external/exchange.
 */
@AuthenticatedOnly
@RestController
@RequestMapping("/api/auth")
public class ExternalAuthController {

    static final String COOKIE = "lms_ext";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    public record ExchangeRequest(@NotBlank String code) {
    }

    private record Begun(String authorizationUrl, ResponseCookie cookie) {
    }

    private final ExternalAuthProperties properties;
    private final ExternalProviderRegistry registry;
    private final ReturnUrlValidator returnUrls;
    private final StateCookieCodec codec;
    private final OidcClient oidc;
    private final ExternalSignInUseCase signIn;
    private final LinkExternalLoginUseCase link;
    private final UnlinkExternalLoginUseCase unlink;
    private final ListExternalLoginsUseCase list;
    private final ExchangeExternalTicketUseCase exchange;
    private final Clock clock;

    public ExternalAuthController(ExternalAuthProperties properties, ExternalProviderRegistry registry,
                                  ReturnUrlValidator returnUrls, StateCookieCodec codec, OidcClient oidc,
                                  ExternalSignInUseCase signIn, LinkExternalLoginUseCase link,
                                  UnlinkExternalLoginUseCase unlink, ListExternalLoginsUseCase list,
                                  ExchangeExternalTicketUseCase exchange, Clock clock) {
        this.properties = properties;
        this.registry = registry;
        this.returnUrls = returnUrls;
        this.codec = codec;
        this.oidc = oidc;
        this.signIn = signIn;
        this.link = link;
        this.unlink = unlink;
        this.list = list;
        this.exchange = exchange;
        this.clock = clock;
    }

    @PublicEndpoint
    @GetMapping("/external/providers")
    public List<ProviderInfo> providers() {
        return registry.list();
    }

    @PublicEndpoint
    @GetMapping("/external/{provider}/start")
    public ResponseEntity<Void> start(@PathVariable String provider, @RequestParam String returnUrl) {
        Begun begun = begin(provider, returnUrl, ExternalState.Mode.LOGIN, null);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(begun.authorizationUrl()))
                .header(HttpHeaders.SET_COOKIE, begun.cookie().toString())
                .cacheControl(CacheControl.noStore()).build();
    }

    /** Trình duyệt không gắn được header Authorization khi điều hướng nên trả JSON; frontend tự chuyển trang. */
    @GetMapping("/me/external/{provider}/link")
    public ResponseEntity<Map<String, String>> startLink(@AuthenticationPrincipal Jwt jwt, @PathVariable String provider,
                                                         @RequestParam String returnUrl) {
        Begun begun = begin(provider, returnUrl, ExternalState.Mode.LINK, CurrentUser.from(jwt).userId());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, begun.cookie().toString())
                .cacheControl(CacheControl.noStore()).body(Map.of("authorizationUrl", begun.authorizationUrl()));
    }

    @PublicEndpoint
    @GetMapping("/external/{provider}/callback")
    public ResponseEntity<?> callback(@PathVariable String provider,
                                      @RequestParam(required = false) String code,
                                      @RequestParam(required = false) String state,
                                      @RequestParam(required = false) String error,
                                      @CookieValue(name = COOKIE, required = false) String cookie) {
        String name = provider.toLowerCase(Locale.ROOT);
        ResponseCookie clear = cookieBuilder("", Duration.ZERO);
        Optional<ExternalState> saved = codec.decode(cookie, clock.instant());
        if (saved.isEmpty() || !saved.get().provider().equals(name)) {
            // Không có returnUrl đáng tin để chuyển về nên trả lỗi trực tiếp
            return ResponseEntity.badRequest().header(HttpHeaders.SET_COOKIE, clear.toString())
                    .body(ErrorResponse.of("INVALID_STATE", "Phiên đăng nhập ngoài không hợp lệ hoặc đã hết hạn"));
        }
        ExternalState s = saved.get();
        if (error != null && !error.isBlank()) {
            return redirect(s.returnUrl(), "authError", "EXTERNAL_DENIED", false, clear);
        }
        if (code == null || state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                s.state().getBytes(StandardCharsets.UTF_8))) {
            return redirect(s.returnUrl(), "authError", Reason.INVALID_STATE.name(), false, clear);
        }
        try {
            Provider p = registry.find(name).orElseThrow(() ->
                    new ExternalAuthException(Reason.PROVIDER_NOT_CONFIGURED, "Nhà cung cấp chưa được cấu hình"));
            ExternalIdentity identity = oidc.exchange(name, p, code, s.codeVerifier(), s.nonce(), redirectUri(name));
            if (s.mode() == ExternalState.Mode.LOGIN) {
                return redirect(s.returnUrl(), "externalCode", signIn.execute(identity), true, clear);
            }
            link.execute(s.linkUserId(), identity);
            return redirect(s.returnUrl(), "linked", name, false, clear);
        } catch (ExternalAuthException e) {
            return redirect(s.returnUrl(), "authError", e.reason().name(), false, clear);
        } catch (AccountLockedException | AccountDisabledException e) {
            return redirect(s.returnUrl(), "authError", "ACCOUNT_UNAVAILABLE", false, clear);
        }
    }

    @PublicEndpoint
    @PostMapping("/external/exchange")
    public ResponseEntity<TokenResponse> exchange(@Valid @RequestBody ExchangeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exchange.execute(request.code()));
    }

    @GetMapping("/me/external")
    public List<ExternalLoginResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return list.execute(CurrentUser.from(jwt).userId());
    }

    @DeleteMapping("/me/external/{provider}")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal Jwt jwt, @PathVariable String provider) {
        unlink.execute(CurrentUser.from(jwt).userId(), provider.toLowerCase(Locale.ROOT));
        return ResponseEntity.noContent().build();
    }

    // ---- nội bộ ----

    private Begun begin(String providerName, String returnUrl, ExternalState.Mode mode, UUID linkUserId) {
        String name = providerName.toLowerCase(Locale.ROOT);
        Provider provider = registry.find(name).orElseThrow(() ->
                new ExternalAuthException(Reason.PROVIDER_NOT_CONFIGURED, "Nhà cung cấp chưa được cấu hình: " + providerName));
        returnUrls.validate(returnUrl);

        String state = Pkce.randomToken(24);
        String nonce = Pkce.randomToken(24);
        String verifier = Pkce.randomToken(32);
        String url = oidc.authorizationUrl(provider, redirectUri(name), state, nonce, Pkce.challenge(verifier));
        String value = codec.encode(new ExternalState(name, state, nonce, verifier, returnUrl.trim(), mode, linkUserId,
                clock.instant().plus(STATE_TTL).getEpochSecond()));
        return new Begun(url, cookieBuilder(value, STATE_TTL));
    }

    private String redirectUri(String provider) {
        String base = properties.getPublicBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/external/" + provider + "/callback";
    }

    private ResponseCookie cookieBuilder(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(properties.isCookieSecure()).sameSite("Lax")
                .path("/api/auth").maxAge(maxAge).build();
    }

    private ResponseEntity<Void> redirect(String returnUrl, String key, String value, boolean fragment, ResponseCookie clear) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(returnUrl);
        URI target = fragment ? b.fragment(key + "=" + value).build().toUri() : b.queryParam(key, value).build().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(target).header(HttpHeaders.SET_COOKIE, clear.toString())
                .cacheControl(CacheControl.noStore()).build();
    }
}
