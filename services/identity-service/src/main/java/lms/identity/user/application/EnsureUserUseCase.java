package lms.identity.user.application;

import java.util.UUID;
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
}
