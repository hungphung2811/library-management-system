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
import java.util.UUID;
import lms.identity.accesscontrol.application.AccessCommands.GrantPermission;
import lms.identity.accesscontrol.application.AccessCommands.UpdateRole;
import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.application.SignupRoleUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.PermissionRepository;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleConflictException;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Cờ signup_default: luật domain (admin không bao giờ mang cờ), use case sửa cờ, use case tìm/gán role đăng ký. */
class SignupRoleTest {

    private final RoleRepository roles = mock(RoleRepository.class);
    private final SaveRolePort save = mock(SaveRolePort.class);
    private final UserRoleUseCase userRoles = mock(UserRoleUseCase.class);
    private final RoleManagementUseCase management = new RoleManagementUseCase(roles, mock(PermissionRepository.class),
            save, mock(UserRoleRepository.class));
    private final SignupRoleUseCase signupRole = new SignupRoleUseCase(roles, userRoles);

    private static Role customer(boolean signup) {
        return Role.restore(RoleId.newId(), "CUSTOMER", "Khách hàng", false, List.of(), UUID.randomUUID(), signup);
    }

    // ---- domain ----

    @Test
    void roleQuanTriKhongBaoGioMangCo() {
        assertThatThrownBy(() -> Role.restore(RoleId.newId(), "OWNER", "Chủ", true, List.of(), null, true))
                .isInstanceOf(InvalidAccessControlException.class);
        assertThatThrownBy(() -> Role.create("OWNER", "Chủ", true).withSignupDefault(true))
                .isInstanceOf(InvalidAccessControlException.class);
        // tắt cờ trên role admin thì vô hại
        assertThat(Role.create("OWNER", "Chủ", true).withSignupDefault(false).isSignupDefault()).isFalse();
    }

    @Test
    void doiTenGiuCoVaBatTatGiuPhanConLai() {
        Role r = customer(true);
        r.grant("ShopController/placeOrder");

        Role renamed = r.rename("SHOPPER", "Người mua");
        assertThat(renamed.isSignupDefault()).isTrue();
        Role off = renamed.withSignupDefault(false);
        assertThat(off.isSignupDefault()).isFalse();
        assertThat(off.id()).isEqualTo(r.id());
        assertThat(off.permissionCodes()).containsExactly("ShopController/placeOrder");
        assertThat(off.tenantId()).isEqualTo(r.tenantId());
    }

    @Test
    void roleTaoMoiVaRestoreCuKhongMangCo() {
        assertThat(Role.create("A_B", "x").isSignupDefault()).isFalse();
        assertThat(Role.restore(RoleId.newId(), "A_B", "x", false, List.of(), null).isSignupDefault()).isFalse();
    }

    // ---- sửa cờ ----

    private Role stored(Role r) {
        when(roles.findById(r.id())).thenReturn(Optional.of(r));
        when(save.save(any())).thenAnswer(i -> i.getArgument(0));
        return r;
    }

    @Test
    void quanTriVienBatCoKhiTenantChuaCoRoleDangKy() {
        Role r = stored(customer(false));
        when(roles.findAll()).thenReturn(List.of(r));

        var res = management.update(new UpdateRole(r.id().value(), "CUSTOMER", "Khách hàng", true, true));

        assertThat(res.signupDefault()).isTrue();
        ArgumentCaptor<Role> saved = ArgumentCaptor.forClass(Role.class);
        verify(save).save(saved.capture());
        assertThat(saved.getValue().isSignupDefault()).isTrue();
    }

    @Test
    void khongPhaiQuanTriVienKhongBatDuocCo() {
        Role r = stored(customer(false));

        assertThatThrownBy(() -> management.update(new UpdateRole(r.id().value(), "CUSTOMER", "Khách hàng", false, true)))
                .isInstanceOf(AccessControlForbiddenException.class);
        verify(save, never()).save(any());
    }

    @Test
    void roleQuanTriKhongBatDuocCoKeCaQuanTriVien() {
        Role admin = stored(Role.create("OWNER", "Chủ", true));

        assertThatThrownBy(() -> management.update(new UpdateRole(admin.id().value(), "OWNER", "Chủ", true, true)))
                .isInstanceOf(InvalidAccessControlException.class);
        verify(save, never()).save(any());
    }

    @Test
    void tenantDaCoRoleDangKyKhacThiConflict() {
        Role other = customer(true);
        Role r = stored(Role.restore(RoleId.newId(), "MEMBER", "Thành viên", false, List.of(), UUID.randomUUID(), false));
        when(roles.findAll()).thenReturn(List.of(other, r));

        assertThatThrownBy(() -> management.update(new UpdateRole(r.id().value(), "MEMBER", "Thành viên", true, true)))
                .isInstanceOf(RoleConflictException.class);
        verify(save, never()).save(any());
    }

    @Test
    void tatCoKhongCanQuanTriVienVaBoQuaKhiNullGiuNguyen() {
        Role r = stored(customer(true));

        assertThat(management.update(new UpdateRole(r.id().value(), "CUSTOMER", "Khách hàng", false, false))
                .signupDefault()).isFalse();
        // null = giữ nguyên cờ hiện có
        assertThat(management.update(new UpdateRole(r.id().value(), "CUSTOMER", "Mô tả mới", false))
                .signupDefault()).isTrue();
    }

    // ---- tìm / gán ----

    @Test
    void timRoleDangKyChiTraRoleCoCo() {
        Role plain = customer(false);
        Role signup = Role.restore(RoleId.newId(), "SHOPPER", "Người mua", false, List.of(), UUID.randomUUID(), true);
        when(roles.findAll()).thenReturn(List.of(plain, signup));

        assertThat(signupRole.find()).contains(signup.id().value());
        when(roles.findAll()).thenReturn(List.of(plain));
        assertThat(signupRole.find()).isEmpty();
    }

    @Test
    void ganRoleDangKyKhongBaoGioLaGanVoiQuyenQuanTri() {
        Role signup = customer(true);
        when(roles.findAll()).thenReturn(List.of(signup));
        UUID userId = UUID.randomUUID();

        signupRole.assignTo(userId);

        ArgumentCaptor<UserRole> c = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoles).assign(c.capture());
        assertThat(c.getValue().userId()).isEqualTo(userId);
        assertThat(c.getValue().roleId()).isEqualTo(signup.id().value());
        assertThat(c.getValue().callerIsAdmin()).isFalse(); // role admin sẽ bị UserRoleUseCase từ chối
    }

    @Test
    void khongCoRoleDangKyThiGanBaoLoi() {
        when(roles.findAll()).thenReturn(List.of(customer(false)));

        assertThatThrownBy(() -> signupRole.assignTo(UUID.randomUUID()))
                .isInstanceOf(AccessControlNotFoundException.class);
        verify(userRoles, never()).assign(any());
    }

    // ---- thêm khóa vào role signup_default (vá leo thang quyền qua đăng ký công khai) ----

    @Test
    void khongAdminKhongThemDuocKhoaVaoRoleDangKyMacDinh() {
        PermissionRepository perms = mock(PermissionRepository.class);
        when(perms.existsByCode("ShopController/placeOrder")).thenReturn(true);
        RoleManagementUseCase uc = new RoleManagementUseCase(roles, perms, save, mock(UserRoleRepository.class));
        Role r = stored(customer(true));

        assertThatThrownBy(() -> uc.grantPermission(new GrantPermission(r.id().value(), "ShopController/placeOrder")))
                .isInstanceOf(AccessControlForbiddenException.class);
        assertThatThrownBy(() -> uc.grantPermission(
                new GrantPermission(r.id().value(), "ShopController/placeOrder", false)))
                .isInstanceOf(AccessControlForbiddenException.class);
        assertThat(r.permissionCodes()).isEmpty();
        verify(save, never()).save(any());
    }

    @Test
    void adminThemDuocKhoaVaoRoleDangKyMacDinh() {
        PermissionRepository perms = mock(PermissionRepository.class);
        when(perms.existsByCode("ShopController/placeOrder")).thenReturn(true);
        RoleManagementUseCase uc = new RoleManagementUseCase(roles, perms, save, mock(UserRoleRepository.class));
        Role r = stored(customer(true));

        uc.grantPermission(new GrantPermission(r.id().value(), "ShopController/placeOrder", true));

        assertThat(r.permissionCodes()).containsExactly("ShopController/placeOrder");
    }

    @Test
    void khongAdminVanThemDuocKhoaVaoRoleThuong() {
        PermissionRepository perms = mock(PermissionRepository.class);
        when(perms.existsByCode("StockItemController/get")).thenReturn(true);
        RoleManagementUseCase uc = new RoleManagementUseCase(roles, perms, save, mock(UserRoleRepository.class));
        Role r = stored(customer(false));

        uc.grantPermission(new GrantPermission(r.id().value(), "StockItemController/get"));

        assertThat(r.permissionCodes()).containsExactly("StockItemController/get");
    }
}
