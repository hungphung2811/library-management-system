package lms.identity.accesscontrol.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.identity.accesscontrol.application.AccessCommands.CreateRole;
import lms.identity.accesscontrol.application.AccessCommands.RegisterPermissionKey;
import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PublishAccessEventPort;
import lms.identity.accesscontrol.application.RegisterPermissionKeyUseCase;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.SavePermissionPort;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.application.SaveUserRolePort;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.domain.EffectivePermissionsService;
import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.Permission;
import lms.identity.accesscontrol.domain.PermissionRepository;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleAssignment;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AdminRoleAndPermissionKeyTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final SaveRolePort saveRole = mock(SaveRolePort.class);
    private final UserRoleRepository assignments = mock(UserRoleRepository.class);
    private final SaveUserRolePort saveAssignment = mock(SaveUserRolePort.class);
    private final PublishAccessEventPort events = mock(PublishAccessEventPort.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final RoleManagementUseCase roleUseCase = new RoleManagementUseCase(roles, permissions, saveRole, assignments);
    private final UserRoleUseCase userRoleUseCase =
            new UserRoleUseCase(roles, assignments, saveAssignment, events, clock);

    // ---- cờ admin khi tạo role ----

    @Test
    void khongPhaiAdminKhongTaoDuocRoleQuanTri() {
        assertThatThrownBy(() -> roleUseCase.create(new CreateRole("SNEAKY", "x", true, false)))
                .isInstanceOf(AccessControlForbiddenException.class);

        verify(saveRole, never()).save(any());
    }

    @Test
    void adminTaoDuocRoleQuanTriVaCoDuocLuuVoiCoAdmin() {
        when(roles.existsByName("SUPERVISOR")).thenReturn(false);
        when(saveRole.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = roleUseCase.create(new CreateRole("SUPERVISOR", "Quản trị", true, true));

        assertThat(response.isAdmin()).isTrue();
        ArgumentCaptor<Role> saved = ArgumentCaptor.forClass(Role.class);
        verify(saveRole).save(saved.capture());
        assertThat(saved.getValue().isAdmin()).isTrue();
    }

    @Test
    void roleThuongAiCoQuyenTaoDeuTaoDuocVaMacDinhKhongPhaiAdmin() {
        when(saveRole.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = roleUseCase.create(new CreateRole("AUDITOR", "Kiểm toán"));

        assertThat(response.isAdmin()).isFalse();
    }

    // ---- gán/thu hồi role quản trị ----

    @Test
    void chiAdminMoiGanVaThuHoiDuocRoleQuanTri() {
        Role adminRole = Role.create("SUPERVISOR", "Quản trị", true);
        UUID user = UUID.randomUUID();
        when(roles.findById(adminRole.id())).thenReturn(Optional.of(adminRole));
        when(assignments.findByUserId(user))
                .thenReturn(Optional.of(UserRoleAssignment.restore(user, List.of(adminRole.id()))));

        assertThatThrownBy(() -> userRoleUseCase.assign(new UserRole(user, adminRole.id().value(), false)))
                .isInstanceOf(AccessControlForbiddenException.class);
        assertThatThrownBy(() -> userRoleUseCase.revoke(new UserRole(user, adminRole.id().value(), false)))
                .isInstanceOf(AccessControlForbiddenException.class);
        verify(saveAssignment, never()).save(any());

        userRoleUseCase.revoke(new UserRole(user, adminRole.id().value(), true));
        verify(saveAssignment).save(any());
    }

    @Test
    void roleThuongAiCoQuyenGanDeuGanDuoc() {
        Role normal = Role.create("MEMBER", "Thành viên");
        UUID user = UUID.randomUUID();
        when(roles.findById(normal.id())).thenReturn(Optional.of(normal));
        when(assignments.findByUserId(user)).thenReturn(Optional.empty());

        userRoleUseCase.assign(new UserRole(user, normal.id().value(), false));

        verify(saveAssignment).save(any());
    }

    @Test
    void nguoiDungLaAdminKhiCoItNhatMotRoleDanhDauAdmin() {
        Role member = Role.create("MEMBER", "Thành viên");
        Role supervisor = Role.create("SUPERVISOR", "Quản trị", true);
        UUID user = UUID.randomUUID();
        when(assignments.findByUserId(user))
                .thenReturn(Optional.of(UserRoleAssignment.restore(user, List.of(member.id(), supervisor.id()))));
        when(roles.findAllByIds(any())).thenReturn(List.of(member, supervisor));
        GetUserAccessUseCase useCase = new GetUserAccessUseCase(assignments, roles, new EffectivePermissionsService());

        UserAccessResponse withAdmin = useCase.execute(user);
        when(roles.findAllByIds(any())).thenReturn(List.of(member));
        UserAccessResponse withoutAdmin = useCase.execute(user);

        assertThat(withAdmin.admin()).isTrue();
        assertThat(withoutAdmin.admin()).isFalse();
    }

    // ---- đăng ký khóa quyền ----

    @Test
    void dangKyKhoaTuSinhGhiNguonAutoVaThongTinEndpoint() {
        SavePermissionPort save = mock(SavePermissionPort.class);
        Instant seen = Instant.parse("2026-02-02T00:00:00Z");

        new RegisterPermissionKeyUseCase(save).execute(new RegisterPermissionKey("circulation-service",
                "BorrowingController/borrow", null, "POST", "/api/borrowings", "", seen));

        ArgumentCaptor<Permission> captured = ArgumentCaptor.forClass(Permission.class);
        verify(save).registerDiscovered(captured.capture());
        Permission p = captured.getValue();
        assertThat(p.code()).isEqualTo("BorrowingController/borrow");
        assertThat(p.source()).isEqualTo("AUTO");
        assertThat(p.service()).isEqualTo("circulation-service");
        assertThat(p.httpMethod()).isEqualTo("POST");
        assertThat(p.pathPattern()).isEqualTo("/api/borrowings");
        assertThat(p.firstSeenAt()).isEqualTo(seen);
        assertThat(p.description()).isEqualTo("BorrowingController/borrow");
    }

    @Test
    void khoaKhongHopLeBiTuChoiTruocKhiChamDb() {
        SavePermissionPort save = mock(SavePermissionPort.class);

        assertThatThrownBy(() -> new RegisterPermissionKeyUseCase(save).execute(new RegisterPermissionKey("svc",
                "khoa co dau cach", "AUTO", "GET", "/x", "d", Instant.now())))
                .isInstanceOf(InvalidAccessControlException.class);
        assertThatThrownBy(() -> new RegisterPermissionKeyUseCase(save).execute(new RegisterPermissionKey("svc",
                "A/b", "KHONG_HOP_LE", "GET", "/x", "d", Instant.now())))
                .isInstanceOf(InvalidAccessControlException.class);

        verify(save, never()).registerDiscovered(any());
    }

    @Test
    void khoaPermissionChapNhanDangControllerSlashMethodVaMaCu() {
        assertThat(new Permission("BorrowingController/borrow", "mô tả").code()).isEqualTo("BorrowingController/borrow");
        assertThat(new Permission("BORROW_BOOK", "mô tả").code()).isEqualTo("BORROW_BOOK");
        assertThatThrownBy(() -> new Permission("có dấu cách", "mô tả")).isInstanceOf(InvalidAccessControlException.class);
    }
}
