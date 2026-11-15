package lms.identity.accesscontrol.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import lms.identity.accesscontrol.application.AccessCommands.DeleteRole;
import lms.identity.accesscontrol.application.AccessCommands.UpdateRole;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.DuplicateAccessControlException;
import lms.identity.accesscontrol.domain.PermissionRepository;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleConflictException;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RoleUpdateDeleteUseCaseTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final SaveRolePort save = mock(SaveRolePort.class);
    private final UserRoleRepository assignments = mock(UserRoleRepository.class);
    private final RoleManagementUseCase useCase = new RoleManagementUseCase(roles, permissions, save, assignments);

    private Role normal() {
        Role r = Role.create("AUDITOR", "Kiểm toán");
        r.grant("BorrowingController/borrow");
        when(roles.findById(r.id())).thenReturn(Optional.of(r));
        return r;
    }

    private Role admin() {
        Role r = Role.create("SUPERVISOR", "Quản trị", true);
        when(roles.findById(r.id())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void suaDoiTenVaMoTaGiuNguyenQuyenVaCoAdmin() {
        Role r = normal();
        when(roles.existsByName("REVIEWER")).thenReturn(false);
        when(save.save(any())).thenAnswer(i -> i.getArgument(0));

        RoleResponse res = useCase.update(new UpdateRole(r.id().value(), "REVIEWER", "Duyệt", false));

        ArgumentCaptor<Role> saved = ArgumentCaptor.forClass(Role.class);
        verify(save).save(saved.capture());
        assertThat(saved.getValue().id()).isEqualTo(r.id());
        assertThat(saved.getValue().isAdmin()).isFalse();
        assertThat(saved.getValue().permissionCodes()).containsExactly("BorrowingController/borrow");
        assertThat(res.name()).isEqualTo("REVIEWER");
        assertThat(res.description()).isEqualTo("Duyệt");
    }

    @Test
    void suaGiuTenCuKhongBaoTrung() {
        Role r = normal();
        when(roles.existsByName("AUDITOR")).thenReturn(true);
        when(save.save(any())).thenAnswer(i -> i.getArgument(0));
        useCase.update(new UpdateRole(r.id().value(), "AUDITOR", "Mô tả mới", false));
        verify(save).save(any());
    }

    @Test
    void suaTrungTenRoleKhacLa409() {
        Role r = normal();
        when(roles.existsByName("LIBRARIAN")).thenReturn(true);
        assertThatThrownBy(() -> useCase.update(new UpdateRole(r.id().value(), "LIBRARIAN", "x", false)))
                .isInstanceOf(DuplicateAccessControlException.class);
        verify(save, never()).save(any());
    }

    @Test
    void suaRoleKhongThayLaNotFound() {
        assertThatThrownBy(() -> useCase.update(new UpdateRole(java.util.UUID.randomUUID(), "A_B", "x", true)))
                .isInstanceOf(AccessControlNotFoundException.class);
    }

    @Test
    void chiAdminMoiSuaDuocRoleQuanTri() {
        Role r = admin();
        assertThatThrownBy(() -> useCase.update(new UpdateRole(r.id().value(), "SUPERVISOR", "m", false)))
                .isInstanceOf(AccessControlForbiddenException.class);
        when(save.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(useCase.update(new UpdateRole(r.id().value(), "SUPERVISOR", "m", true)).isAdmin()).isTrue();
    }

    @Test
    void xoaRoleChuaGanChoAi() {
        Role r = normal();
        when(assignments.existsByRoleId(r.id().value())).thenReturn(false);
        useCase.delete(new DeleteRole(r.id().value(), false));
        verify(save).delete(r);
    }

    @Test
    void xoaRoleDangDuocGanLaRoleInUse() {
        Role r = normal();
        when(assignments.existsByRoleId(r.id().value())).thenReturn(true);
        assertThatThrownBy(() -> useCase.delete(new DeleteRole(r.id().value(), true)))
                .isInstanceOfSatisfying(RoleConflictException.class, e -> assertThat(e.code()).isEqualTo("ROLE_IN_USE"));
        verify(save, never()).delete(any());
    }

    @Test
    void xoaRoleQuanTriDuyNhatBiChan() {
        Role r = admin();
        when(roles.findAll()).thenReturn(List.of(r, Role.create("LIBRARIAN", "TV")));
        assertThatThrownBy(() -> useCase.delete(new DeleteRole(r.id().value(), true)))
                .isInstanceOfSatisfying(RoleConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("LAST_ADMIN_ROLE"));
    }

    @Test
    void xoaRoleQuanTriKhiConRoleQuanTriKhacThiDuocNeuKhongAiDuocGan() {
        Role r = admin();
        when(roles.findAll()).thenReturn(List.of(r, Role.create("OWNER", "Chủ", true)));
        when(assignments.existsByRoleId(r.id().value())).thenReturn(false);
        useCase.delete(new DeleteRole(r.id().value(), true));
        verify(save).delete(r);
    }

    @Test
    void khongPhaiAdminKhongXoaDuocRoleQuanTri() {
        Role r = admin();
        assertThatThrownBy(() -> useCase.delete(new DeleteRole(r.id().value(), false)))
                .isInstanceOf(AccessControlForbiddenException.class);
        verify(save, never()).delete(any());
    }

    @Test
    void xoaRoleKhongThayLaNotFound() {
        assertThatThrownBy(() -> useCase.delete(new DeleteRole(java.util.UUID.randomUUID(), true)))
                .isInstanceOf(AccessControlNotFoundException.class);
    }
}
