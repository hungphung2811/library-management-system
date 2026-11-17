package lms.customer.storefront;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class ShopSessionGuardTest {

    private final UUID tenant = UUID.randomUUID();
    private final StorefrontAccess shop = new StorefrontAccess(tenant, "shop-a", "A", "svc-token", 300);
    private final ShopSessionGuard guard = new ShopSessionGuard();

    private static JwtAuthenticationToken token(String claim, Object value) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString())
                .audience(java.util.List.of("lms-storefront"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (claim != null) {
            b.claim(claim, value);
        }
        return new JwtAuthenticationToken(b.build());
    }

    @Test
    void tenantKhopThiChoQuaVaTraJwtCuaKhach() {
        JwtAuthenticationToken auth = token("tenantId", tenant.toString());
        assertSame(auth.getToken(), guard.requireSession(shop, auth));
    }

    @Test
    void tenantKhacBi403SessionMismatch() {
        PublicApiException ex = assertThrows(PublicApiException.class,
                () -> guard.requireSession(shop, token("tenantId", UUID.randomUUID().toString())));
        assertEquals(403, ex.status().value());
        assertEquals("SHOP_SESSION_MISMATCH", ex.code());
    }

    @Test
    void tokenKhongCoTenantHoacTenantRacBi403() {
        assertEquals("SHOP_SESSION_MISMATCH",
                assertThrows(PublicApiException.class, () -> guard.requireSession(shop, token(null, null))).code());
        assertEquals("SHOP_SESSION_MISMATCH",
                assertThrows(PublicApiException.class, () -> guard.requireSession(shop, token("tenantId", "khong-phai-uuid"))).code());
    }

    @Test
    void tokenDichVuSvcBi401DuTenantKhop() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(UUID.randomUUID().toString())
                .claim("tenantId", tenant.toString()).claim("svc", "customer-api").build();
        PublicApiException ex = assertThrows(PublicApiException.class,
                () -> guard.requireSession(shop, new JwtAuthenticationToken(jwt)));
        assertEquals(401, ex.status().value());
    }

    @Test
    void tokenKhongCoAudStorefrontBi401DuTenantKhop() {
        // Token nhân viên (không aud hoặc lms-staff) không được vào route khách (docs/api/token-audience.md).
        for (java.util.List<String> aud : java.util.List.of(java.util.List.<String>of(), java.util.List.of("lms-staff"))) {
            Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("u")
                    .claim("tenantId", tenant.toString());
            if (!aud.isEmpty()) {
                b.audience(aud);
            }
            PublicApiException ex = assertThrows(PublicApiException.class,
                    () -> guard.requireSession(shop, new JwtAuthenticationToken(b.build())));
            assertEquals(401, ex.status().value());
        }
    }

    @Test
    void chuaDangNhapBi401() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken("k", "anon",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertEquals(401, assertThrows(PublicApiException.class, () -> guard.requireSession(shop, anon)).status().value());
        assertEquals(401, assertThrows(PublicApiException.class, () -> guard.requireSession(shop, null)).status().value());
    }
}
