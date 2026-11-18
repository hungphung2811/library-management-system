package lms.identity.accesscontrol.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.cache.RequestCache;
import lms.identity.accesscontrol.application.AccessCommands.CreateRole;
import lms.identity.accesscontrol.application.AccessCommands.DeleteRole;
import lms.identity.accesscontrol.application.AccessCommands.GrantPermission;
import lms.identity.accesscontrol.application.AccessCommands.UpdateRole;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.domain.PermissionRepository;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decorator cache cho GET danh sách/chi tiết role (quản trị) và evict ở mọi thao tác ghi role. Chỉ phủ
 * {@link RoleManagementUseCase}; {@code GetUserAccessUseCase} (cấp quyền khi đăng nhập) và gán role cho người dùng
 * không được cache. RoleResponse không phụ thuộc người gọi nên key không cần userId.
 */
public class CachedRoleManagementUseCase extends RoleManagementUseCase {

    public static final String NAMESPACE = "identity.roles";
    static final Duration TTL = Duration.ofSeconds(30);

    private final RequestCache cache;

    public CachedRoleManagementUseCase(RoleRepository roles, PermissionRepository permissions, SaveRolePort savePort,
                                       UserRoleRepository assignments, RequestCache cache) {
        super(roles, permissions, savePort, assignments);
        this.cache = cache;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return cache.get(NAMESPACE, "list", TTL, new TypeReference<List<RoleResponse>>() { }, super::list);
    }

    @Override
    @Transactional(readOnly = true)
    public RoleResponse get(UUID roleId) {
        return cache.get(NAMESPACE, "get:" + roleId, TTL, RoleResponse.class, () -> super.get(roleId));
    }

    @Override
    @Transactional
    public RoleResponse create(CreateRole command) {
        RoleResponse response = super.create(command);
        cache.evictNamespace(NAMESPACE);
        return response;
    }

    @Override
    @Transactional
    public RoleResponse update(UpdateRole command) {
        RoleResponse response = super.update(command);
        cache.evictNamespace(NAMESPACE);
        return response;
    }

    @Override
    @Transactional
    public void delete(DeleteRole command) {
        super.delete(command);
        cache.evictNamespace(NAMESPACE);
    }

    @Override
    @Transactional
    public RoleResponse grantPermission(GrantPermission command) {
        RoleResponse response = super.grantPermission(command);
        cache.evictNamespace(NAMESPACE);
        return response;
    }

    @Override
    @Transactional
    public RoleResponse revokePermission(UUID roleId, String permissionCode) {
        RoleResponse response = super.revokePermission(roleId, permissionCode);
        cache.evictNamespace(NAMESPACE);
        return response;
    }
}
