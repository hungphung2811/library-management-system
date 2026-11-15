package lms.identity.accesscontrol.infrastructure.platform;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import lms.identity.tenant.application.TenantRoleProvisioningPort;
import lms.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cung cấp {@link TenantRoleProvisioningPort} cho context tenant. Mọi thao tác chạy trong tenant hiện tại của
 * TenantContext: role mới nhận tenant_id qua @TenantId, RLS FORCE kiểm thêm ở DB (người gọi đã mở transaction bên trong
 * {@code TenantContext.callAs(tenant)}, R6). role_templates là bảng toàn cục, đọc bằng SQL hẹp.
 */
public class TenantRoleProvisioningAdapter implements TenantRoleProvisioningPort {

    private final RoleRepository roles;
    private final SaveRolePort saveRole;
    private final UserRoleRepository assignments;
    private final UserRoleUseCase userRoles;
    private final JdbcTemplate jdbc;

    public TenantRoleProvisioningAdapter(RoleRepository roles, SaveRolePort saveRole, UserRoleRepository assignments,
                                         UserRoleUseCase userRoles, JdbcTemplate jdbc) {
        this.roles = roles;
        this.saveRole = saveRole;
        this.assignments = assignments;
        this.userRoles = userRoles;
        this.jdbc = jdbc;
    }

    private record Template(String name, String description, boolean admin, Set<String> permissions) {
    }

    @Override
    public int seedFromTemplates() {
        TenantContext.require();
        Map<UUID, Template> templates = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, description, is_admin FROM role_templates ORDER BY name", rs -> {
            templates.put(rs.getObject("id", UUID.class), new Template(rs.getString("name"),
                    rs.getString("description"), rs.getBoolean("is_admin"), new LinkedHashSet<>()));
        });
        jdbc.query("SELECT template_id, permission_code FROM role_template_permissions ORDER BY permission_code", rs -> {
            Template t = templates.get(rs.getObject("template_id", UUID.class));
            if (t != null) t.permissions().add(rs.getString("permission_code"));
        });
        for (Template t : templates.values()) {
            saveRole.save(Role.restore(RoleId.newId(), t.name(), t.description(), t.admin(), t.permissions()));
        }
        return templates.size();
    }

    @Override
    public void grantTenantAdmin(UUID userId) {
        Role adminRole = adminRoles().stream().min(Comparator.comparing(Role::name))
                .orElseThrow(() -> new IllegalStateException("Tenant chưa có role quản trị (isAdmin)"));
        // người gọi là người vận hành: được gán role quản trị
        userRoles.assign(new UserRole(userId, adminRole.id().value(), true));
    }

    @Override
    public boolean revokeTenantAdmin(UUID userId) {
        Set<RoleId> held = assignments.findByUserId(userId).map(a -> a.roleIds()).orElse(Set.of());
        List<Role> toRevoke = adminRoles().stream().filter(r -> held.contains(r.id())).toList();
        for (Role role : toRevoke) {
            userRoles.revoke(new UserRole(userId, role.id().value(), true));
        }
        return !toRevoke.isEmpty();
    }

    @Override
    public List<UUID> listTenantAdmins() {
        UUID tenantId = TenantContext.require();
        // lọc tenant tường minh (ngoài RLS) để không phụ thuộc vai trò DB đang dùng
        return jdbc.queryForList("SELECT DISTINCT ur.user_id FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE r.is_admin AND r.tenant_id = ? AND ur.tenant_id = ? ORDER BY ur.user_id", UUID.class,
                tenantId, tenantId);
    }

    private List<Role> adminRoles() {
        return roles.findAll().stream().filter(Role::isAdmin).toList();
    }
}
