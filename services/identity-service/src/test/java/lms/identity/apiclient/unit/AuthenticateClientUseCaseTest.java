package lms.identity.apiclient.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.ApplicationViews.CreatedCredential;
import lms.identity.apiclient.application.AuthenticateClientUseCase;
import lms.identity.apiclient.application.AuthenticateClientUseCase.AuthenticatedClient;
import lms.identity.apiclient.application.CreateApplicationUseCase.Command;
import lms.identity.tenant.application.TenantAvailabilityQuery;
import lms.identity.tenant.application.TenantAvailabilityQuery.Availability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Xác thực client của token endpoint OAuth2 (SaaS API Authentication pha I2): mọi lý do thất bại cùng một kết quả rỗng,
 * hai credential ACTIVE đều dùng được, scope theo module của tenant, last_used_at tối đa một lần mỗi giờ.
 */
class AuthenticateClientUseCaseTest {

    private final ApiClientFixture f = new ApiClientFixture();
    private final UUID tenant = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final TenantAvailabilityQuery tenants = mock(TenantAvailabilityQuery.class);
    private AuthenticateClientUseCase auth;
    private ApplicationDetail erp;
    private CreatedCredential cred;

    @BeforeEach
    void setUp() {
        auth = new AuthenticateClientUseCase(f.applications, f.credentials, tenants, f.tx, f.clock);
        when(tenants.execute(any())).thenReturn(new Availability(false, List.of()));
        when(tenants.execute(tenant)).thenReturn(new Availability(true, List.of("LENDING", "SALES")));
        erp = f.create.execute(tenant, new Command("ERP", "INTEGRATION", 600), admin);
        f.manage.replaceScopes(tenant, erp.id(), List.of("books:read", "inventory:read", "orders:read"), admin);
        cred = f.creds.create(tenant, erp.id(), null, admin);
    }

    private Optional<AuthenticatedClient> login(String id, String secret) {
        return auth.authenticate(id, secret);
    }

    @Test
    void hopLeTraScopeDaCapVaKhaDungTheoModule() {
        AuthenticatedClient c = login(erp.id().toString(), cred.clientSecret()).orElseThrow();

        assertThat(c.applicationId()).isEqualTo(erp.id());
        assertThat(c.tenantId()).isEqualTo(tenant);
        assertThat(c.grantedScopes()).containsExactly("books:read", "inventory:read", "orders:read");
        assertThat(c.availableScopes()).containsExactly("books:read", "inventory:read", "orders:read");
        assertThat(c.modules()).containsExactlyInAnyOrder("LENDING", "SALES");
    }

    @Test
    void tenantTatModuleSalesLoaiScopeInventoryVaOrders() {
        when(tenants.execute(tenant)).thenReturn(new Availability(true, List.of("LENDING")));

        AuthenticatedClient c = login(erp.id().toString(), cred.clientSecret()).orElseThrow();

        assertThat(c.grantedScopes()).containsExactly("books:read", "inventory:read", "orders:read");
        assertThat(c.availableScopes()).containsExactly("books:read");
    }

    @Test
    void moiLyDoThatBaiCungMotKetQuaRong() {
        String id = erp.id().toString();
        String secret = cred.clientSecret();
        // secret sai / thiếu / rỗng
        assertThat(login(id, secret + "x")).isEmpty();
        assertThat(login(id, null)).isEmpty();
        assertThat(login(id, "")).isEmpty();
        // client_id không tồn tại / không phải UUID / null
        assertThat(login(UUID.randomUUID().toString(), secret)).isEmpty();
        assertThat(login("khong-phai-uuid", secret)).isEmpty();
        assertThat(login(null, secret)).isEmpty();
        // tenant không hoạt động
        when(tenants.execute(tenant)).thenReturn(new Availability(false, List.of()));
        assertThat(login(id, secret)).isEmpty();
        when(tenants.execute(tenant)).thenReturn(new Availability(true, List.of("SALES")));
        assertThat(login(id, secret)).isPresent();
    }

    @Test
    void applicationTamNgungVaThuHoiBiTuChoiConHoatDongLaiThiDuocDung() {
        String id = erp.id().toString();

        f.manage.suspend(tenant, erp.id(), admin);
        assertThat(login(id, cred.clientSecret())).isEmpty();

        f.manage.reactivate(tenant, erp.id(), admin);
        assertThat(login(id, cred.clientSecret())).isPresent();

        f.manage.delete(tenant, erp.id(), admin);
        assertThat(login(id, cred.clientSecret())).isEmpty();
    }

    @Test
    void credentialThuHoiHoacHetHanBiTuChoi() {
        CreatedCredential second = f.creds.create(tenant, erp.id(), 1, admin);
        String id = erp.id().toString();

        f.creds.revoke(tenant, erp.id(), cred.credentialId(), "ROTATED", admin);
        assertThat(login(id, cred.clientSecret())).isEmpty();
        assertThat(login(id, second.clientSecret())).isPresent();

        f.clock.advance(Duration.ofDays(1).plusSeconds(1));
        assertThat(login(id, second.clientSecret())).isEmpty();
    }

    @Test
    void haiCredentialActiveCungXacThucDuocTrongGiaiDoanXoayVong() {
        CreatedCredential second = f.creds.create(tenant, erp.id(), null, admin);
        String id = erp.id().toString();

        assertThat(login(id, cred.clientSecret())).isPresent();
        assertThat(login(id, second.clientSecret())).isPresent();
    }

    @Test
    void websiteKhongCoCredentialNenKhongDangNhapDuoc() {
        ApplicationDetail site = f.create.execute(tenant, new Command("Web", "PUBLIC_SITE", null), admin);

        assertThat(login(site.id().toString(), cred.clientSecret())).isEmpty();
    }

    @Test
    void secretCuaApplicationKhacKhongDungDuoc() {
        ApplicationDetail other = f.create.execute(tenant, new Command("CRM", "INTEGRATION", null), admin);
        f.manage.replaceScopes(tenant, other.id(), List.of("books:read"), admin);

        assertThat(login(other.id().toString(), cred.clientSecret())).isEmpty();
    }

    @Test
    void lastUsedAtChiGhiToiDaMotLanMoiGio() {
        String id = erp.id().toString();
        assertThat(f.credentials.rows.get(cred.credentialId()).lastUsedAt()).isNull();

        login(id, cred.clientSecret());
        var first = f.credentials.rows.get(cred.credentialId()).lastUsedAt();
        assertThat(first).isEqualTo(f.clock.instant());

        f.clock.advance(Duration.ofMinutes(59));
        login(id, cred.clientSecret());
        assertThat(f.credentials.rows.get(cred.credentialId()).lastUsedAt()).isEqualTo(first);

        f.clock.advance(Duration.ofMinutes(2));
        login(id, cred.clientSecret());
        assertThat(f.credentials.rows.get(cred.credentialId()).lastUsedAt()).isEqualTo(f.clock.instant());
    }

    @Test
    void thatBaiKhongGhiLastUsedAt() {
        login(erp.id().toString(), cred.clientSecret() + "x");

        assertThat(f.credentials.rows.get(cred.credentialId()).lastUsedAt()).isNull();
    }
}
