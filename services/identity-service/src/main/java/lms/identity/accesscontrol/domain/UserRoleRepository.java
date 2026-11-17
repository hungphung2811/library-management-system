package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.UserRoleAssignment;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleRepository {
    Optional<UserRoleAssignment> findByUserId(UUID userId);

    /** Còn người dùng (trong tenant hiện tại) đang được gán role này không. */
    boolean existsByRoleId(UUID roleId);

    /** Role của nhiều người dùng (trong tenant hiện tại) một lượt; người không có role nào không có trong map. */
    default java.util.Map<UUID, java.util.Set<RoleId>> findRoleIdsByUserIds(java.util.Collection<UUID> userIds) {
        java.util.Map<UUID, java.util.Set<RoleId>> result = new java.util.HashMap<>();
        for (UUID id : userIds) {
            findByUserId(id).ifPresent(a -> {
                if (!a.roleIds().isEmpty()) result.put(id, a.roleIds());
            });
        }
        return result;
    }
}
