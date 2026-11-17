package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** API của context access-control cho context khác: tên role (trong tenant hiện tại) của nhiều người dùng, tra theo lô. */
public class ListUserRoleNamesUseCase {

    private final UserRoleRepository assignments;
    private final RoleRepository roles;

    public ListUserRoleNamesUseCase(UserRoleRepository assignments, RoleRepository roles) {
        this.assignments = assignments;
        this.roles = roles;
    }

    /** Tên role sắp theo bảng chữ cái; người không có role nào không có trong map. */
    public Map<UUID, List<String>> execute(Collection<UUID> userIds) {
        Map<UUID, Set<RoleId>> byUser = assignments.findRoleIdsByUserIds(userIds);
        if (byUser.isEmpty()) return Map.of();
        Map<RoleId, String> names = new HashMap<>();
        for (Role r : roles.findAllByIds(byUser.values().stream().flatMap(Set::stream).distinct().toList())) {
            names.put(r.id(), r.name());
        }
        Map<UUID, List<String>> result = new HashMap<>();
        byUser.forEach((user, ids) -> result.put(user,
                ids.stream().map(names::get).filter(n -> n != null).sorted().toList()));
        return result;
    }
}
