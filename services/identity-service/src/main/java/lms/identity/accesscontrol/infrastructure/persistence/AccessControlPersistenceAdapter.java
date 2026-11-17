package lms.identity.accesscontrol.infrastructure.persistence;

import lms.identity.accesscontrol.application.SavePermissionPort;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.application.SaveUserRolePort;
import lms.identity.accesscontrol.domain.UserRoleAssignment;
import lms.identity.accesscontrol.domain.Permission;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.domain.PermissionRepository;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Adapter persistence duy nhất cho cả ba aggregate; tách Domain Model khỏi Persistence Model. */
public class AccessControlPersistenceAdapter implements RoleRepository, PermissionRepository, UserRoleRepository,
        SaveRolePort, SavePermissionPort, SaveUserRolePort {

    private final RoleJpaRepository roleJpa;
    private final PermissionJpaRepository permissionJpa;
    private final UserRoleJpaRepository userRoleJpa;

    public AccessControlPersistenceAdapter(RoleJpaRepository roleJpa, PermissionJpaRepository permissionJpa,
                                           UserRoleJpaRepository userRoleJpa) {
        this.roleJpa = roleJpa;
        this.permissionJpa = permissionJpa;
        this.userRoleJpa = userRoleJpa;
    }

    // ---- Role ----

    @Override
    public Role save(Role role) {
        RoleJpaEntity saved = roleJpa.save(new RoleJpaEntity(role.id().value(), role.name(), role.description(),
                role.isAdmin(), role.permissionCodes(), role.isSignupDefault()));
        return Role.restore(role.id(), role.name(), role.description(), role.isAdmin(), role.permissionCodes(),
                saved.getTenantId(), saved.isSignupDefault());
    }

    @Override
    public void delete(Role role) {
        roleJpa.deleteById(role.id().value());
    }

    @Override
    public boolean existsByRoleId(UUID roleId) {
        return userRoleJpa.existsByRoleId(roleId);
    }

    @Override
    public Optional<Role> findById(RoleId id) {
        return roleJpa.findById(id.value()).map(AccessControlPersistenceAdapter::toDomain);
    }

    @Override
    public boolean existsByName(String name) {
        return roleJpa.existsByName(name);
    }

    @Override
    public List<Role> findAllByIds(Collection<RoleId> ids) {
        return roleJpa.findAllById(ids.stream().map(RoleId::value).toList()).stream()
                .map(AccessControlPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<Role> findAll() {
        return roleJpa.findAll().stream().map(AccessControlPersistenceAdapter::toDomain).toList();
    }

    // ---- Permission ----

    @Override
    public Permission save(Permission permission) {
        permissionJpa.save(new PermissionJpaEntity(permission.code(), permission.description(), permission.source(),
                permission.service(), permission.httpMethod(), permission.pathPattern(), permission.firstSeenAt()));
        return permission;
    }

    @Override
    public void registerDiscovered(Permission permission) {
        permissionJpa.registerDiscovered(permission.code(), permission.description(), permission.source(),
                permission.service(), permission.httpMethod(), permission.pathPattern(), permission.firstSeenAt());
    }

    @Override
    public boolean existsByCode(String code) {
        return permissionJpa.existsById(code);
    }

    @Override
    public List<Permission> findAllPermissions() {
        return permissionJpa.findAll().stream()
                .map(e -> new Permission(e.getCode(), e.getDescription(), e.getSource(), e.getService(),
                        e.getHttpMethod(), e.getPathPattern(), e.getFirstSeenAt()))
                .toList();
    }

    // ---- UserRoleAssignment ----

    @Override
    public UserRoleAssignment save(UserRoleAssignment assignment) {
        Set<UUID> wanted = assignment.roleIds().stream().map(RoleId::value).collect(Collectors.toSet());
        Set<UUID> existing = userRoleJpa.findByUserId(assignment.userId()).stream()
                .map(UserRoleJpaEntity::getRoleId).collect(Collectors.toCollection(HashSet::new));

        existing.stream().filter(r -> !wanted.contains(r))
                .forEach(r -> userRoleJpa.deleteById(new UserRoleJpaEntity.Key(assignment.userId(), r)));
        wanted.stream().filter(r -> !existing.contains(r))
                .forEach(r -> userRoleJpa.save(new UserRoleJpaEntity(assignment.userId(), r)));
        return assignment;
    }

    @Override
    public java.util.Map<UUID, java.util.Set<lms.identity.accesscontrol.domain.RoleId>> findRoleIdsByUserIds(
            java.util.Collection<UUID> userIds) {
        java.util.Map<UUID, java.util.Set<lms.identity.accesscontrol.domain.RoleId>> result = new java.util.HashMap<>();
        if (userIds.isEmpty()) return result;
        for (UserRoleJpaEntity row : userRoleJpa.findByUserIdIn(userIds)) {
            result.computeIfAbsent(row.getUserId(), k -> new java.util.LinkedHashSet<>())
                    .add(new lms.identity.accesscontrol.domain.RoleId(row.getRoleId()));
        }
        return result;
    }

    @Override
    public Optional<UserRoleAssignment> findByUserId(UUID userId) {
        List<UserRoleJpaEntity> rows = userRoleJpa.findByUserId(userId);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(UserRoleAssignment.restore(userId,
                rows.stream().map(r -> new RoleId(r.getRoleId())).toList()));
    }

    private static Role toDomain(RoleJpaEntity e) {
        return Role.restore(new RoleId(e.getId()), e.getName(), e.getDescription(), e.isAdmin(),
                e.getPermissionCodes(), e.getTenantId(), e.isSignupDefault());
    }
}
