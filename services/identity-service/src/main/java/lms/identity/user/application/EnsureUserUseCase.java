package lms.identity.user.application;

import java.util.UUID;
import lms.identity.user.domain.UserPersonalInfo;
import lms.identity.user.domain.UserRepository;
import lms.tenant.TenantContext;

/**
 * Tạo người dùng trong tenant hiện tại nếu chưa có (theo id), qua {@link CreateUserUseCase}. Dùng để seed dữ liệu demo
 * idempotent (ADR-0018). Không @Transactional: mỗi lần tạo là một transaction của CreateUserUseCase.
 */
public class EnsureUserUseCase {

    private final UserRepository repository;
    private final CreateUserUseCase createUser;

    public EnsureUserUseCase(UserRepository repository, CreateUserUseCase createUser) {
        this.repository = repository;
        this.createUser = createUser;
    }

    /** @return true nếu vừa tạo, false nếu đã là thành viên của tenant hiện tại (không cập nhật gì) */
    public boolean execute(CreateUserRequest request) {
        UUID tenantId = TenantContext.require();
        if (request.id() != null && repository.findInTenant(request.id(), tenantId).isPresent()) return false;
        createUser.execute(request);
        return true;
    }

    /**
     * Điền thông tin cá nhân còn thiếu cho người dùng đã có trong tenant hiện tại: chỉ trường đang null được điền, giá
     * trị đã có không bị đè (seed chạy lại không ghi đè dữ liệu đã sửa).
     *
     * @return true nếu có ghi thay đổi
     */
    public boolean fillMissing(UUID userId, UserPersonalInfo defaults) {
        return repository.findInTenant(userId, TenantContext.require()).map(t -> {
            if (!t.user().fillMissingPersonalInfo(defaults)) return false;
            repository.save(t.user());
            return true;
        }).orElse(false);
    }
}
