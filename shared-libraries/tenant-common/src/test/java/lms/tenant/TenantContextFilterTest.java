package lms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.security.JwtClaims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void login(String tenantId) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (tenantId != null) jwt.claim(JwtClaims.TENANT_ID, tenantId);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), List.of()));
    }

    @Test
    void claimTenantIdDuocDatTrongRequestVaXoaSau() throws Exception {
        UUID tenant = UUID.randomUUID();
        login(tenant.toString());
        List<Optional<UUID>> seen = new ArrayList<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.add(TenantContext.current()));

        assertThat(seen).containsExactly(Optional.of(tenant));
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void xoaContextKeCaKhiChainNemLoi() {
        login(UUID.randomUUID().toString());

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); })).isInstanceOf(ServletException.class);

        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void khongCoClaimThiContextRongVaRequestVanDiTiep() throws Exception {
        login(null);
        List<Optional<UUID>> seen = new ArrayList<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> seen.add(TenantContext.current()));

        assertThat(seen).containsExactly(Optional.empty());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void khongDangNhapThiContextRong() throws Exception {
        List<Optional<UUID>> seen = new ArrayList<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new MockFilterChain() {
                    @Override
                    public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                        seen.add(TenantContext.current());
                    }
                });

        assertThat(seen).containsExactly(Optional.empty());
    }

    @Test
    void claimKhongPhaiUuidThiBoQuaVaContextRong() throws Exception {
        login("khong-phai-uuid");
        List<Optional<UUID>> seen = new ArrayList<>();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.add(TenantContext.current()));

        assertThat(seen).containsExactly(Optional.empty());
    }
}
