package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository {
    Optional<Role> findById(RoleId id);

    boolean existsByName(String name);

    List<Role> findAllByIds(Collection<RoleId> ids);

    List<Role> findAll();
}
