package lms.identity.user.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.user.application.AccountCredentialPort;
import lms.identity.user.application.AccountCredentialPort.AccountSession;
import lms.identity.user.application.ListUserSessionsUseCase;
import lms.identity.user.application.RevokeUserSessionUseCase;
import lms.identity.user.application.RevokeUserSessionsInTenantUseCase;
import lms.identity.user.application.SessionCaller;
import lms.identity.user.application.TargetAdminLookupPort;
import lms.identity.user.application.UserSessionView;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserSessionNotFoundException;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

/** Quy tắc G1-G6 của API phiên quản trị (ADR-0017 mục 2.9.3) cho cả ba use case. */
class UserSessionRulesTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID sid = UUID.randomUUID();
    private final UserRepository repo = mock(UserRepository.class);
    private final AccountCredentialPort credentials = mock(AccountCredentialPort.class);
    private final TargetAdminLookupPort adminLookup = mock(TargetAdminLookupPort.class);

    private final ListUserSessionsUseCase list = new ListUserSessionsUseCase(repo, credentials, adminLookup);
    private final RevokeUserSessionUseCase revokeOne = new RevokeUserSessionUseCase(repo, credentials, adminLookup);
    private final RevokeUserSessionsInTenantUseCase revokeAll =
            new RevokeUserSessionsInTenantUseCase(repo, credentials, adminLookup);

    private static SessionCaller staff() { return new SessionCaller(UUID.randomUUID(), false, false); }
    private static SessionCaller admin() { return new SessionCaller(UUID.randomUUID(), true, false); }
    private static SessionCaller platform() { return new SessionCaller(UUID.randomUUID(), false, true); }

    private void member() {
        when(repo.hasMembership(target, tenant)).thenReturn(true);
    }

    private <T> T inTenant(java.util.function.Supplier<T> s) {
        return TenantContext.callAs(tenant, s);
    }

    @Test
    void g1KhongCoTenantHienTaiBiTuChoiTruocMoiThuTra() {
        assertThatThrownBy(() -> list.execute(admin(), target)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> revokeOne.execute(admin(), target, sid)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> revokeAll.execute(admin(), target)).isInstanceOf(RuntimeException.class);
        org.mockito.Mockito.verifyNoInteractions(credentials);
    }

    @Test
    void g2NguoiNgoaiTenantLaUserNotFoundOCaBaUseCase() {
        when(repo.hasMembership(target, tenant)).thenReturn(false);
        assertThatThrownBy(() -> inTenant(() -> list.execute(admin(), target))).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> inTenant(() -> { revokeOne.execute(admin(), target, sid); return null; }))
                .isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> inTenant(() -> revokeAll.execute(admin(), target)))
                .isInstanceOf(UserNotFoundException.class);
        verify(credentials, never()).revokeSessionsInTenant(any(), any(), any());
        verify(credentials, never()).revokeSessionInTenant(any(), any(), any(), any());
        verify(credentials, never()).sessionsInTenant(any(), any(), any());
    }

    @Test
    void g3DichLaAdminTenantChiAdminDuocThaoTacKeCaRevokeSessionsDaCo() {
        member();
        when(adminLookup.isTenantAdmin(target)).thenReturn(true);
        when(credentials.sessionsInTenant(any(), any(), any())).thenReturn(List.of());
        when(credentials.revokeSessionsInTenant(any(), any(), any())).thenReturn(2);
        when(credentials.revokeSessionInTenant(any(), any(), any(), any())).thenReturn(true);

        SessionCaller staff = staff();
        for (var op : List.<java.util.function.Consumer<SessionCaller>>of(
                c -> list.execute(c, target),
                c -> revokeOne.execute(c, target, sid),
                c -> revokeAll.execute(c, target))) {
            assertThatThrownBy(() -> inTenant(() -> { op.accept(staff); return null; }))
                    .isInstanceOf(UserOperationNotAllowedException.class);
            // admin tenant thao tác lên admin: được
            inTenant(() -> { op.accept(admin()); return null; });
        }
        verify(credentials, org.mockito.Mockito.times(1)).sessionsInTenant(any(), any(), any());
        verify(credentials, org.mockito.Mockito.times(1)).revokeSessionsInTenant(any(), any(), any());
        verify(credentials, org.mockito.Mockito.times(1)).revokeSessionInTenant(any(), any(), any(), any());
    }

    @Test
    void g3NguoiThuongChiBiChanKhiDichLaAdminCoNguoiThuongThiDuoc() {
        member();
        when(adminLookup.isTenantAdmin(target)).thenReturn(false);
        when(credentials.revokeSessionsInTenant(eq(target), eq(tenant), any())).thenReturn(1);

        assertThat(inTenant(() -> revokeAll.execute(staff(), target))).isEqualTo(1);
    }

    @Test
    void g4DichLaPlatformAdminChiPlatformAdminDuocThaoTac() {
        member();
        when(credentials.isPlatformAdmin(target)).thenReturn(true);
        when(credentials.revokeSessionsInTenant(any(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> inTenant(() -> revokeAll.execute(admin(), target)))
                .isInstanceOf(UserOperationNotAllowedException.class);
        assertThatThrownBy(() -> inTenant(() -> list.execute(admin(), target)))
                .isInstanceOf(UserOperationNotAllowedException.class);
        assertThatThrownBy(() -> inTenant(() -> { revokeOne.execute(admin(), target, sid); return null; }))
                .isInstanceOf(UserOperationNotAllowedException.class);

        assertThat(inTenant(() -> revokeAll.execute(platform(), target))).isEqualTo(1);
    }

    @Test
    void g5TuThaoTacLenMinhVanQuaCacQuyTacNhungKhongPhaiAdminThiKhongXemDuocPhienMinhKhiMinhLaAdmin() {
        // Chính mình có role admin nhưng gọi bằng token không admin (token cũ): bị chặn như người khác (G3)
        UUID self = target;
        member();
        when(adminLookup.isTenantAdmin(self)).thenReturn(true);
        SessionCaller selfNotAdminClaim = new SessionCaller(self, false, false);
        assertThatThrownBy(() -> inTenant(() -> list.execute(selfNotAdminClaim, target)))
                .isInstanceOf(UserOperationNotAllowedException.class);
        // Có claim admin: được, và là tự xem nên không audit (actor null)
        when(credentials.sessionsInTenant(self, tenant, null)).thenReturn(List.of());
        inTenant(() -> list.execute(new SessionCaller(self, true, false), target));
        verify(credentials).sessionsInTenant(self, tenant, null);
    }

    @Test
    void xemPhienNguoiKhacTruyenActorDeAuditVaAnhXaDayDuIp() {
        member();
        Instant t = Instant.parse("2026-11-17T00:00:00Z");
        when(credentials.sessionsInTenant(eq(target), eq(tenant), any())).thenReturn(List.of(
                new AccountSession(sid, "STAFF", "Chrome trên Windows", "113.161.45.9", t, t, t.plusSeconds(1))));
        SessionCaller caller = admin();

        List<UserSessionView> views = inTenant(() -> list.execute(caller, target));

        verify(credentials).sessionsInTenant(target, tenant, caller.userId());
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.ipAddress()).isEqualTo("113.161.45.9"); // IP đầy đủ, không che
            assertThat(v.deviceSummary()).isEqualTo("Chrome trên Windows");
        });
    }

    @Test
    void tuThuHoiMotPhienCuaMinhTruyenActorNullDeKhongPhatAudit() {
        member();
        when(credentials.revokeSessionInTenant(target, tenant, sid, null)).thenReturn(true);
        inTenant(() -> { revokeOne.execute(new SessionCaller(target, true, false), target, sid); return null; });
        verify(credentials).revokeSessionInTenant(target, tenant, sid, null);
    }

    @Test
    void tuThuHoiTatCaPhienCuaMinhTruyenActorNullDeKhongPhatAudit() {
        member();
        when(credentials.revokeSessionsInTenant(target, tenant, null)).thenReturn(3);
        assertThat(inTenant(() -> revokeAll.execute(new SessionCaller(target, true, false), target))).isEqualTo(3);
        verify(credentials).revokeSessionsInTenant(target, tenant, null);
    }

    @Test
    void thuHoiPhienNguoiKhacTruyenActorLaNguoiGoiDePhatDungMotSuKien() {
        member();
        SessionCaller caller = admin();
        when(credentials.revokeSessionInTenant(target, tenant, sid, caller.userId())).thenReturn(true);
        when(credentials.revokeSessionsInTenant(target, tenant, caller.userId())).thenReturn(2);
        inTenant(() -> { revokeOne.execute(caller, target, sid); return null; });
        assertThat(inTenant(() -> revokeAll.execute(caller, target))).isEqualTo(2);
        verify(credentials).revokeSessionInTenant(target, tenant, sid, caller.userId());
        verify(credentials).revokeSessionsInTenant(target, tenant, caller.userId());
    }

    @Test
    void thuHoiMotPhienKhongKhopLaSessionNotFoundVaTenantLayTuContextKhongTuNguoiGoi() {
        member();
        when(credentials.revokeSessionInTenant(target, tenant, sid, null)).thenReturn(false);
        SessionCaller caller = new SessionCaller(UUID.randomUUID(), true, false);
        when(credentials.revokeSessionInTenant(eq(target), eq(tenant), eq(sid), eq(caller.userId()))).thenReturn(false);

        assertThatThrownBy(() -> inTenant(() -> { revokeOne.execute(caller, target, sid); return null; }))
                .isInstanceOf(UserSessionNotFoundException.class);
        // tenant truyền xuống luôn là tenant của TenantContext
        verify(credentials).revokeSessionInTenant(target, tenant, sid, caller.userId());
    }
}
