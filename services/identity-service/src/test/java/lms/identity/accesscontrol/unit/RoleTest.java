package lms.identity.accesscontrol.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.Permission;
import lms.identity.accesscontrol.domain.Role;
import org.junit.jupiter.api.Test;

class RoleTest {

    @Test
    void tenRoleVaMaPermissionPhaiLaUpperSnake() {
        assertThatThrownBy(() -> Role.create("librarian", "Thủ thư")).isInstanceOf(InvalidAccessControlException.class);
        assertThatThrownBy(() -> new Permission("book view", "Xem sách")).isInstanceOf(InvalidAccessControlException.class);
    }

    @Test
    void capPermissionLaIdempotent() {
        Role role = Role.create("LIBRARIAN", "Thủ thư");

        role.grant("BOOK_VIEW");
        role.grant("BOOK_VIEW");

        assertThat(role.permissionCodes()).containsExactly("BOOK_VIEW");
    }

    @Test
    void thuHoiPermissionKhongCoThiBaoLoi() {
        Role role = Role.create("LIBRARIAN", "Thủ thư");
        role.grant("BOOK_VIEW");

        role.revoke("BOOK_VIEW");

        assertThat(role.permissionCodes()).isEmpty();
        assertThatThrownBy(() -> role.revoke("BOOK_VIEW")).isInstanceOf(InvalidAccessControlException.class);
    }
}
