package lms.identity.accesscontrol.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.application.PublishAccessEventPort;
import lms.identity.accesscontrol.application.SaveUserRolePort;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.domain.RoleAssignedEvent;
import lms.identity.accesscontrol.domain.RoleRevokedEvent;
import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.UserRoleAssignment;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import lms.identity.accesscontrol.domain.EffectivePermissionsService;
import lms.common.kernel.DomainEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UserRoleUseCaseTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRoleRepository assignments = mock(UserRoleRepository.class);
    private final SaveUserRolePort savePort = mock(SaveUserRolePort.class);
    private final PublishAccessEventPort eventPort = mock(PublishAccessEventPort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final UserRoleUseCase useCase = new UserRoleUseCase(roles, assignments, savePort, eventPort, clock);

    private final UUID userId = UUID.randomUUID();
    private final Role librarian = roleWith("LIBRARIAN", "BOOK_VIEW", "BORROW_BOOK");

    private static Role roleWith(String name, String... permissions) {
        Role role = Role.create(name, "mô tả");
        for (String p : permissions) role.grant(p);
        return role;
    }

    @Test
    void ganRoleLuuVaPhatEventRoleAssigned() {
        when(roles.findById(librarian.id())).thenReturn(Optional.of(librarian));
        when(assignments.findByUserId(userId)).thenReturn(Optional.empty());

        useCase.assign(new UserRole(userId, librarian.id().value()));

        verify(savePort).save(any(UserRoleAssignment.class));
        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPort).publish(event.capture());
        assertThat(event.getValue()).isInstanceOf(RoleAssignedEvent.class);
    }

    @Test
    void ganLaiRoleDaCoKhongPhatEvent() {
        when(roles.findById(librarian.id())).thenReturn(Optional.of(librarian));
        when(assignments.findByUserId(userId))
                .thenReturn(Optional.of(UserRoleAssignment.restore(userId, List.of(librarian.id()))));

        useCase.assign(new UserRole(userId, librarian.id().value()));

        verify(eventPort, never()).publish(any());
    }

    @Test
    void thuHoiRolePhatEventRoleRevoked() {
        when(roles.findById(librarian.id())).thenReturn(Optional.of(librarian));
        when(assignments.findByUserId(userId))
                .thenReturn(Optional.of(UserRoleAssignment.restore(userId, List.of(librarian.id()))));

        useCase.revoke(new UserRole(userId, librarian.id().value()));

        ArgumentCaptor<DomainEvent> event = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPort, times(1)).publish(event.capture());
        assertThat(event.getValue()).isInstanceOf(RoleRevokedEvent.class);
    }

    @Test
    void thuHoiRoleChuaCoBaoLoi() {
        when(roles.findById(librarian.id())).thenReturn(Optional.of(librarian));
        when(assignments.findByUserId(userId)).thenReturn(Optional.of(UserRoleAssignment.empty(userId)));

        assertThatThrownBy(() -> useCase.revoke(new UserRole(userId, librarian.id().value())))
                .isInstanceOf(InvalidAccessControlException.class);
        verify(savePort, never()).save(any());
    }

    @Test
    void roleKhongTonTaiBaoNotFound() {
        UUID unknown = UUID.randomUUID();
        when(roles.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.assign(new UserRole(userId, unknown)))
                .isInstanceOf(AccessControlNotFoundException.class);
    }

    @Test
    void permissionHieuLucLaHopCuaCacRole() {
        Role admin = roleWith("LIBRARY_ADMIN", "BOOK_VIEW", "USER_VIEW");
        when(assignments.findByUserId(userId)).thenReturn(
                Optional.of(UserRoleAssignment.restore(userId, List.of(librarian.id(), admin.id()))));
        when(roles.findAllByIds(any())).thenReturn(List.of(librarian, admin));

        UserAccessResponse response = new GetUserAccessUseCase(assignments, roles, new EffectivePermissionsService())
                .execute(userId);

        assertThat(response.roles()).containsExactly("LIBRARIAN", "LIBRARY_ADMIN");
        assertThat(response.permissions()).containsExactly("BOOK_VIEW", "BORROW_BOOK", "USER_VIEW");
    }

    @Test
    void nguoiDungChuaCoRoleThiKhongCoQuyen() {
        when(assignments.findByUserId(userId)).thenReturn(Optional.empty());

        UserAccessResponse response = new GetUserAccessUseCase(assignments, roles, new EffectivePermissionsService())
                .execute(userId);

        assertThat(response.roles()).isEmpty();
        assertThat(response.permissions()).isEmpty();
    }

    // ---- phạm vi tenant (spec 5.6): kho role chỉ trả role của tenant hiện tại (@TenantId + RLS) ----

    @Test
    void khongGanHayThuHoiDuocRoleNgoaiTenantMinh() {
        Role roleOfOtherTenant = roleWith("B_ONLY");
        // kho role của tenant hiện tại không thấy role của tenant khác
        when(roles.findById(roleOfOtherTenant.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.assign(new UserRole(userId, roleOfOtherTenant.id().value(), true)))
                .isInstanceOf(AccessControlNotFoundException.class);
        assertThatThrownBy(() -> useCase.revoke(new UserRole(userId, roleOfOtherTenant.id().value(), true)))
                .isInstanceOf(AccessControlNotFoundException.class);
        verify(savePort, never()).save(any());
        verify(eventPort, never()).publish(any());
    }

    @Test
    void chiQuanTriVienMoiGanDuocRoleQuanTriCuaTenant() {
        Role tenantAdmin = Role.create("TENANT_ADMIN", "Quản trị tenant", true);
        when(roles.findById(tenantAdmin.id())).thenReturn(Optional.of(tenantAdmin));
        when(assignments.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.assign(new UserRole(userId, tenantAdmin.id().value(), false)))
                .isInstanceOf(AccessControlForbiddenException.class);
        verify(savePort, never()).save(any());

        useCase.assign(new UserRole(userId, tenantAdmin.id().value(), true));
        verify(savePort).save(any(UserRoleAssignment.class));
    }
}
