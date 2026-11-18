package lms.identity.auth.application;

import java.util.UUID;
import lms.identity.auth.domain.SignupNotAvailableException;
import lms.identity.tenant.application.StorefrontTenant;
import lms.identity.tenant.application.TransactionalRunner;
import lms.tenant.TenantContext;

/**
 * Tự đăng ký của khách (ADR-0010 B2.1, ADR-0011): endpoint công khai nên KHÔNG tin đầu vào nào ngoài mã tenant công
 * khai. Quy tắc:
 * <ol>
 *   <li>Tenant tồn tại, ACTIVE và bật SALES (tự kiểm, vì resolver nay cũng nhận tenant chỉ LENDING; {@link StorefrontTenantPort}, cùng nguồn với token storefront), nếu không
 *       thì {@link SignupNotAvailableException}.</li>
 *   <li>Tenant có role mang cờ signup_default và role đó không phải admin, nếu không thì cùng
 *       {@link SignupNotAvailableException} (một mã, không lộ tenant).</li>
 *   <li>Trong MỘT transaction bắt đầu SAU khi đặt TenantContext (roles/user_roles chịu RLS FORCE): tạo hồ sơ +
 *       credential (không mustChangePassword) + membership, rồi gán role signup_default. Lỗi ở bước nào thì không ghi
 *       gì (chỉ ghi identity_db).</li>
 * </ol>
 * Command không có role/flag/tenantId: role luôn là role signup_default do dữ liệu tenant quyết, nên người gọi không tự
 * cấp quyền cho mình (mass assignment) và không bao giờ tạo được tài khoản admin.
 */
public class SignupUseCase {

    private final StorefrontTenantPort tenants;
    private final SignupAccountPort accounts;
    private final SignupRolePort roles;
    private final TransactionalRunner tx;

    public SignupUseCase(StorefrontTenantPort tenants, SignupAccountPort accounts, SignupRolePort roles,
                         TransactionalRunner tx) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.roles = roles;
        this.tx = tx;
    }

    /** tenantCode: mã công khai của cửa hàng (customer-api lấy từ đường dẫn, không từ body của khách). */
    public record Command(String tenantCode, String username, String password, String fullName, String email) {

        /** Không in mật khẩu, họ tên, email. */
        @Override
        public String toString() {
            return "Command[tenantCode=" + tenantCode + ", username=" + username + "]";
        }
    }

    /** Trả id người dùng mới tạo (không đưa ra ngoài API). */
    public UUID execute(Command command) {
        StorefrontTenant tenant = tenants.resolve(command.tenantCode()).orElseThrow(SignupNotAvailableException::new);
        if (!tenant.modules().contains("SALES")) throw new SignupNotAvailableException();
        return TenantContext.callAs(tenant.id(), () -> tx.inTransaction(() -> {
            if (!roles.available()) throw new SignupNotAvailableException();
            UUID userId = accounts.register(command.username(), command.password(), command.fullName(),
                    command.email());
            roles.assignTo(userId);
            return userId;
        }));
    }
}
