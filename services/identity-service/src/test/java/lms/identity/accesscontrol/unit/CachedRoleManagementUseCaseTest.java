package lms.identity.accesscontrol.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.cache.NoOpRequestCache;
import lms.identity.accesscontrol.application.AccessCommands.CreateRole;
import lms.identity.accesscontrol.application.AccessCommands.DeleteRole;
import lms.identity.accesscontrol.application.AccessCommands.GrantPermission;
import lms.identity.accesscontrol.application.AccessCommands.UpdateRole;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.domain.PermissionRepository;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import lms.identity.accesscontrol.infrastructure.cache.CachedRoleManagementUseCase;
import lms.identity.cache.InMemoryRequestCache;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CachedRoleManagementUseCaseTest {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** Kho role trong bộ nhớ, đếm số lần đọc (chạm "DB"). */
    static class Store implements RoleRepository, SaveRolePort {
        final List<Role> roles = new ArrayList<>();
        int reads;

        public Optional<Role> findById(RoleId id) { reads++; return roles.stream().filter(r -> r.id().equals(id)).findFirst(); }
        public boolean existsByName(String name) { return roles.stream().anyMatch(r -> r.name().equals(name)); }
        public List<Role> findAllByIds(Collection<RoleId> ids) { return roles.stream().filter(r -> ids.contains(r.id())).toList(); }
        public List<Role> findAll() { reads++; return List.copyOf(roles); }
        public Role save(Role role) { roles.removeIf(r -> r.id().equals(role.id())); roles.add(role); return role; }
        public void delete(Role role) { roles.removeIf(r -> r.id().equals(role.id())); }
    }

    private final Store store = new Store();
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final UserRoleRepository assignments = mock(UserRoleRepository.class);
    private final InMemoryRequestCache cache = new InMemoryRequestCache();
    private final RoleManagementUseCase useCase =
            new CachedRoleManagementUseCase(store, permissions, store, assignments, cache);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void listVaGetLanHaiKhongChamDb() {
        TenantContext.set(TENANT_A);
        RoleResponse created = useCase.create(new CreateRole("AUDITOR", "Kiểm toán"));

        List<RoleResponse> first = useCase.list();
        int reads = store.reads;
        assertThat(useCase.list()).isEqualTo(first).hasSize(1);
        assertThat(store.reads).isEqualTo(reads);

        RoleResponse one = useCase.get(created.id());
        reads = store.reads;
        assertThat(useCase.get(created.id())).isEqualTo(one);
        assertThat(store.reads).isEqualTo(reads);
    }

    @Test
    void ghiXongGetSauThayDuLieuMoi() {
        TenantContext.set(TENANT_A);
        RoleResponse r = useCase.create(new CreateRole("AUDITOR", "Kiểm toán"));
        assertThat(useCase.list()).hasSize(1);
        assertThat(useCase.get(r.id()).permissions()).isEmpty();

        // grant: chi tiết và danh sách thấy quyền mới
        when(permissions.existsByCode(anyString())).thenReturn(true);
        useCase.grantPermission(new GrantPermission(r.id(), "BorrowingController/borrow", true));
        assertThat(useCase.get(r.id()).permissions()).containsExactly("BorrowingController/borrow");
        assertThat(useCase.list().get(0).permissions()).containsExactly("BorrowingController/borrow");

        // revoke
        useCase.revokePermission(r.id(), "BorrowingController/borrow");
        assertThat(useCase.get(r.id()).permissions()).isEmpty();

        // update
        useCase.update(new UpdateRole(r.id(), "REVIEWER", "Duyệt", true));
        assertThat(useCase.get(r.id()).name()).isEqualTo("REVIEWER");
        assertThat(useCase.list().get(0).name()).isEqualTo("REVIEWER");

        // create
        useCase.create(new CreateRole("OTHER", "Khác"));
        assertThat(useCase.list()).hasSize(2);

        // delete
        useCase.delete(new DeleteRole(r.id(), true));
        assertThat(useCase.list()).hasSize(1);
    }

    @Test
    void haiTenantKhongThayCacheCuaNhau() {
        Store storeB = new Store();
        RoleManagementUseCase useCaseB =
                new CachedRoleManagementUseCase(storeB, permissions, storeB, assignments, cache);
        TenantContext.set(TENANT_A);
        useCase.create(new CreateRole("ROLE_A", "Của A"));
        assertThat(useCase.list()).hasSize(1);

        TenantContext.set(TENANT_B);
        assertThat(useCaseB.list()).isEmpty();
        useCaseB.create(new CreateRole("ROLE_B", "Của B"));

        TenantContext.set(TENANT_A);
        assertThat(useCase.list()).extracting(RoleResponse::name).containsExactly("ROLE_A");
    }

    @Test
    void cacheTatThiHanhViGiongCu() {
        RoleManagementUseCase off = new CachedRoleManagementUseCase(store, permissions, store, assignments,
                new NoOpRequestCache());
        TenantContext.set(TENANT_A);
        off.create(new CreateRole("AUDITOR", "Kiểm toán"));
        off.list();
        int reads = store.reads;
        off.list();
        assertThat(store.reads).isEqualTo(reads + 1);
    }
}
