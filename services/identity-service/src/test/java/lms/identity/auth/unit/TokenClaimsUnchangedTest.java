package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.UserCredential;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

/**
 * ADR-0022 (B3): claim của token không đổi sau khi bỏ câu đọc module trùng. Nguồn module là
 * {@code choice.available()}; chỉ khi tenant không nằm trong danh sách mới đọc lại qua {@code tenantAccess.modules}.
 * Role/permission/admin theo tenant đã chọn (đọc trong TenantContext của tenant đó), như trước.
 */
class TokenClaimsUnchangedTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID tenantEmpty = UUID.randomUUID();
    private final UUID tenantHidden = UUID.randomUUID();

    private final TenantSummary summaryA = new TenantSummary(tenantA, "a", "A", List.of("LENDING", "SALES"));
    private final TenantSummary summaryB = new TenantSummary(tenantB, "b", "B", List.of("LENDING"));
    private final TenantSummary summaryEmpty = new TenantSummary(tenantEmpty, "e", "E", List.of("LENDING"));
    private final List<TenantSummary> all = List.of(summaryA, summaryB, summaryEmpty);

    private final List<TokenIssuerPort.Claims> issued = new ArrayList<>();
    private final List<RefreshToken> saved = new ArrayList<>();
    private final TenantAccessPort tenants = mock(TenantAccessPort.class);

    /** Role/permission/admin theo tenant của TenantContext hiện tại (như dữ liệu role thuộc tenant). */
    private final Map<UUID, UserAccessPort.UserAccess> accessByTenant = Map.of(
            tenantA, new UserAccessPort.UserAccess(UUID.randomUUID(), List.of("LIBRARIAN", "MEMBER"),
                    List.of("BorrowingController/borrow", "TitleController/list"), false),
            tenantB, new UserAccessPort.UserAccess(UUID.randomUUID(), List.of("OWNER"),
                    List.of("SalesController/sell"), true),
            tenantEmpty, new UserAccessPort.UserAccess(UUID.randomUUID(), List.of(), List.of(), false));

    private final SaveRefreshTokenPort refreshSave = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { saved.add(t); return t; }
        public void revokeAllByUserId(UUID u) { }
        public int revokeSession(UUID s, lms.identity.auth.domain.RevokeReason r, Instant n) { return 1; }
    };
    private final RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
        public String generate() { return "raw"; }
        public String hash(String raw) { return "h-" + raw; }
    };
    private final TokenService service = new TokenService(
            id -> accessByTenant.getOrDefault(TenantContext.current().orElse(null),
                    new UserAccessPort.UserAccess(id, List.of(), List.of(), false)),
            c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
            codec, refreshSave, clock, Duration.ofDays(7), id -> java.util.Optional.empty(), tenants);

    private UserCredential credential(boolean platformAdmin) {
        return UserCredential.restore(userId, "alice", "h", null, true, 0, null, false, platformAdmin);
    }

    @Test
    void nhieuTenantNhieuRoleDangNhapRoiDoiTenantMoiTokenMangDungClaimCuaTenantDo() {
        when(tenants.selectForLogin(userId, "a", false)).thenReturn(new TenantChoice(tenantA, all, null));

        service.issueFor(credential(false), "a");
        service.issueForTenant(credential(false), new TenantChoice(tenantB, all, null));

        TokenIssuerPort.Claims a = issued.get(0);
        assertThat(a.tenantId()).isEqualTo(tenantA);
        assertThat(a.roles()).containsExactly("LIBRARIAN", "MEMBER");
        assertThat(a.permissions()).containsExactly("BorrowingController/borrow", "TitleController/list");
        assertThat(a.admin()).isFalse();
        assertThat(a.modules()).containsExactly("LENDING", "SALES");
        assertThat(a.platformAdmin()).isFalse();
        assertThat(a.sessionId()).isEqualTo(saved.get(0).sessionId());

        TokenIssuerPort.Claims b = issued.get(1);
        assertThat(b.tenantId()).isEqualTo(tenantB);
        assertThat(b.roles()).containsExactly("OWNER");
        assertThat(b.permissions()).containsExactly("SalesController/sell");
        assertThat(b.admin()).isTrue();
        assertThat(b.modules()).containsExactly("LENDING");
        assertThat(b.sessionId()).isEqualTo(saved.get(1).sessionId());

        // nguồn module là choice.available(): không đọc lại
        verify(tenants, never()).modules(any());
    }

    @Test
    void tenantKhongCoRoleNaoTokenKhongRoleKhongPermissionKhongAdminNhungVanCoModules() {
        service.issueForTenant(credential(false), new TenantChoice(tenantEmpty, all, null));

        TokenIssuerPort.Claims c = issued.get(0);
        assertThat(c.tenantId()).isEqualTo(tenantEmpty);
        assertThat(c.roles()).isEmpty();
        assertThat(c.permissions()).isEmpty();
        assertThat(c.admin()).isFalse();
        assertThat(c.modules()).containsExactly("LENDING");
        verify(tenants, never()).modules(any());
    }

    @Test
    void platformAdminKhongTenantKhongRoleKhongModulesCoCoPlatformAdmin() {
        service.issueForTenant(credential(true), new TenantChoice(null, List.of(), null));

        TokenIssuerPort.Claims c = issued.get(0);
        assertThat(c.tenantId()).isNull();
        assertThat(c.platformAdmin()).isTrue();
        assertThat(c.admin()).isFalse();
        assertThat(c.roles()).isEmpty();
        assertThat(c.permissions()).isEmpty();
        assertThat(c.modules()).isNull();
        verify(tenants, never()).modules(any());
    }

    @Test
    void tenantKhongNamTrongAvailableThiDocLaiModulesNhuTruoc() {
        when(tenants.modules(tenantHidden)).thenReturn(List.of("SALES"));

        service.issueForTenant(credential(true), new TenantChoice(tenantHidden, all, null));

        assertThat(issued.get(0).modules()).containsExactly("SALES");
        verify(tenants).modules(tenantHidden);
    }
}
