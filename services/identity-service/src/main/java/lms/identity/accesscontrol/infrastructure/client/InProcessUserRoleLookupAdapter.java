package lms.identity.accesscontrol.infrastructure.client;

import lms.identity.accesscontrol.application.ListUserRoleNamesUseCase;
import lms.identity.user.application.UserRoleLookupPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cổng tra tên role của user (port của context user), cài đặt ở phía access-control để user không phải biết access-control. */
public class InProcessUserRoleLookupAdapter implements UserRoleLookupPort {

    private final ListUserRoleNamesUseCase listRoleNames;

    public InProcessUserRoleLookupAdapter(ListUserRoleNamesUseCase listRoleNames) {
        this.listRoleNames = listRoleNames;
    }

    @Override
    public Map<UUID, List<String>> roleNames(Collection<UUID> userIds) {
        return listRoleNames.execute(userIds);
    }
}
