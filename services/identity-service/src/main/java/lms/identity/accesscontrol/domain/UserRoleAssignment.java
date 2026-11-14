package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.RoleAssignedEvent;
import lms.identity.accesscontrol.domain.RoleRevokedEvent;
import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.RoleId;
import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Aggregate Root: tập role của một người dùng (một User có nhiều Role). */
public class UserRoleAssignment {

    private final UUID userId;
    private final Set<RoleId> roleIds;
    private final List<DomainEvent> events = new ArrayList<>();

    private UserRoleAssignment(UUID userId, Collection<RoleId> roleIds) {
        if (userId == null) throw new InvalidAccessControlException("userId không được null");
        this.userId = userId;
        this.roleIds = new LinkedHashSet<>(roleIds);
    }

    public static UserRoleAssignment empty(UUID userId) {
        return new UserRoleAssignment(userId, Set.of());
    }

    public static UserRoleAssignment restore(UUID userId, Collection<RoleId> roleIds) {
        return new UserRoleAssignment(userId, roleIds);
    }

    /** Idempotent: gán lại role đã có không phát sinh event. */
    public void assign(RoleId roleId, String roleName, Instant now) {
        if (roleIds.add(roleId)) {
            events.add(new RoleAssignedEvent(UUID.randomUUID(), userId, roleId.value(), roleName, now));
        }
    }

    public void revoke(RoleId roleId, String roleName, Instant now) {
        if (!roleIds.remove(roleId)) {
            throw new InvalidAccessControlException("Người dùng chưa có role " + roleName);
        }
        events.add(new RoleRevokedEvent(UUID.randomUUID(), userId, roleId.value(), roleName, now));
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public UUID userId() { return userId; }
    public Set<RoleId> roleIds() { return Set.copyOf(roleIds); }
}
