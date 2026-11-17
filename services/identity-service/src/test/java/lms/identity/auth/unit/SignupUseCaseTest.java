package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.SignupAccountPort;
import lms.identity.auth.application.SignupRolePort;
import lms.identity.auth.application.SignupUseCase;
import lms.identity.auth.application.SignupUseCase.Command;
import lms.identity.auth.application.StorefrontTenantPort;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.InvalidSignupException;
import lms.identity.auth.domain.SignupNotAvailableException;
import lms.identity.tenant.application.StorefrontTenant;
import lms.identity.tenant.application.TransactionalRunner;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;

/**
 * Tự đăng ký (ADR-0010 B2.1): tenant phải ACTIVE + SALES (qua StorefrontTenantPort) và có role signup_default; mọi lý do
 * không đăng ký được cùng một lỗi; tài khoản + role ghi trong cùng TenantContext; Command không mang role/flag.
 */
class SignupUseCaseTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID newUser = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final List<UUID> tenantSeenByCalls = new ArrayList<>();

    private boolean tenantAvailable = true;
    private boolean roleAvailable = true;
    private RuntimeException registerFails;
    private RuntimeException assignFails;

    private final StorefrontTenantPort tenants = code -> {
        calls.add("resolve:" + code);
        return tenantAvailable
                ? Optional.of(new StorefrontTenant(tenantId, "shop", "Shop", List.of("SALES")))
                : Optional.empty();
    };

    private final SignupAccountPort accounts = (username, password, fullName, email) -> {
        calls.add("register:" + username + "/" + fullName + "/" + email);
        tenantSeenByCalls.add(TenantContext.current().orElse(null));
        if (registerFails != null) throw registerFails;
        return newUser;
    };

    private final SignupRolePort roles = new SignupRolePort() {
        public boolean available() {
            calls.add("roleAvailable");
            tenantSeenByCalls.add(TenantContext.current().orElse(null));
            return roleAvailable;
        }

        public void assignTo(UUID userId) {
            calls.add("assign:" + (userId.equals(newUser) ? "newUser" : userId));
            tenantSeenByCalls.add(TenantContext.current().orElse(null));
            if (assignFails != null) throw assignFails;
        }
    };

    private final SignupUseCase useCase = new SignupUseCase(tenants, accounts, roles, new TransactionalRunner());

    private static Command command() {
        return new Command("shop", "bob", "secret123", "Bob", "bob@example.com");
    }

    @Test
    void thanhCongTaoTaiKhoanRoiGanRoleSignupDefaultTrongDungTenant() {
        UUID id = useCase.execute(command());

        assertThat(id).isEqualTo(newUser);
        assertThat(calls).containsExactly("resolve:shop", "roleAvailable", "register:bob/Bob/bob@example.com",
                "assign:newUser");
        assertThat(tenantSeenByCalls).containsOnly(tenantId); // roles/user_roles chịu RLS: mọi bước trong tenant của cửa hàng
        assertThat(TenantContext.current()).isEmpty();          // và không rò TenantContext ra ngoài
    }

    @Test
    void tenantKhongKhaDungKhongBatKyGhiNao() {
        tenantAvailable = false; // không tồn tại / bị ngưng / chưa bật SALES: cùng một kết quả rỗng từ port

        assertThatThrownBy(() -> useCase.execute(command())).isInstanceOf(SignupNotAvailableException.class);

        assertThat(calls).containsExactly("resolve:shop");
    }

    @Test
    void tenantKhongCoRoleSignupDefaultCungMotLoiVaKhongTaoTaiKhoan() {
        roleAvailable = false;

        assertThatThrownBy(() -> useCase.execute(command()))
                .isInstanceOf(SignupNotAvailableException.class)
                .hasMessage(SignupNotAvailableException.MESSAGE);

        assertThat(calls).containsExactly("resolve:shop", "roleAvailable");
    }

    @Test
    void lyDoKhongKhaDungKhongPhanBietDuoc() {
        tenantAvailable = false;
        Throwable noTenant = org.assertj.core.api.Assertions.catchThrowable(() -> useCase.execute(command()));
        tenantAvailable = true;
        roleAvailable = false;
        Throwable noRole = org.assertj.core.api.Assertions.catchThrowable(() -> useCase.execute(command()));

        assertThat(noTenant).isExactlyInstanceOf(noRole.getClass()).hasMessage(noRole.getMessage());
    }

    @Test
    void trungTaiKhoanKhongGanRole() {
        registerFails = DuplicateUsernameException.generic();

        assertThatThrownBy(() -> useCase.execute(command())).isSameAs(registerFails);

        assertThat(calls).doesNotContain("assign:newUser");
    }

    @Test
    void hoSoKhongHopLeKhongGanRole() {
        registerFails = new InvalidSignupException("Email không hợp lệ");

        assertThatThrownBy(() -> useCase.execute(command())).isInstanceOf(InvalidSignupException.class);

        assertThat(calls).noneMatch(c -> c.startsWith("assign"));
    }

    @Test
    void ganRoleLoiThiLoiLenTrenDeTransactionRollbackToanBo() {
        assignFails = new IllegalStateException("db");

        assertThatThrownBy(() -> useCase.execute(command())).isSameAs(assignFails);
    }

    @Test
    void commandKhongCoChoNaoDeMangRoleHayCoTenantId() {
        assertThat(Command.class.getRecordComponents()).extracting(rc -> rc.getName())
                .containsExactly("tenantCode", "username", "password", "fullName", "email");
        assertThat(command().toString()).doesNotContain("secret123").doesNotContain("bob@example.com")
                .doesNotContain("Bob");
    }
}
