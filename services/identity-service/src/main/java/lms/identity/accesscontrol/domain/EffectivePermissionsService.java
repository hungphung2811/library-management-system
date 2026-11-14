package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.Role;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/** Domain Service: permission hiệu lực của người dùng là hợp permission của mọi role họ có. */
public class EffectivePermissionsService {

    public Set<String> resolve(Collection<Role> roles) {
        Set<String> permissions = new TreeSet<>();
        roles.forEach(r -> permissions.addAll(r.permissionCodes()));
        return permissions;
    }
}
