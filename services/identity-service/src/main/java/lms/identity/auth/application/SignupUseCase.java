package lms.identity.auth.application;

import java.util.Optional;
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
    /** null hoặc tắt = hành vi cũ (tài khoản mới coi như đã xác minh email). */
    private final EmailVerificationUseCase verification;

    public SignupUseCase(StorefrontTenantPort tenants, SignupAccountPort accounts, SignupRolePort roles,
                         TransactionalRunner tx) {
        this(tenants, accounts, roles, tx, null);
    }

    public SignupUseCase(StorefrontTenantPort tenants, SignupAccountPort accounts, SignupRolePort roles,
                         TransactionalRunner tx, EmailVerificationUseCase verification) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.roles = roles;
        this.tx = tx;
        this.verification = verification;
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
        // Xác minh email (audit A3): tài khoản chưa xác minh + hash OTP ghi trong CÙNG transaction đăng ký; thư chỉ gửi sau commit.
        Optional<EmailVerificationUseCase.Challenge>[] challenge = newHolder();
        UUID id = TenantContext.callAs(tenant.id(), () -> tx.inTransaction(() -> {
            if (!roles.available()) throw new SignupNotAvailableException();
            UUID userId = accounts.register(command.username(), command.password(), command.fullName(),
                    command.email());
            roles.assignTo(userId);
            if (verification != null) challenge[0] = verification.begin(userId, command.email());
            return userId;
        }));
        if (challenge[0].isPresent()) verification.deliver(challenge[0].get());
        return id;
    }

    @SuppressWarnings("unchecked")
    private static Optional<EmailVerificationUseCase.Challenge>[] newHolder() {
        Optional<EmailVerificationUseCase.Challenge>[] holder = new Optional[1];
        holder[0] = Optional.empty();
        return holder;
    }
}
