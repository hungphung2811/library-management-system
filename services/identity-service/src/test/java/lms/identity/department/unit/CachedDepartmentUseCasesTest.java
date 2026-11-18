package lms.identity.department.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.cache.NoOpRequestCache;
import lms.identity.cache.InMemoryRequestCache;
import lms.identity.department.application.CreateDepartmentRequest;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.DepartmentNode;
import lms.identity.department.application.DepartmentResponse;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentRequest;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.DepartmentStatus;
import lms.identity.department.infrastructure.cache.CachedDepartmentUseCases;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CachedDepartmentUseCasesTest {

    static final UUID TENANT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** Kho theo tenant, đếm số lần findAll (số lần chạm "DB"). */
    static class Repo implements DepartmentRepository {
        final List<Department> store = new ArrayList<>();
        int findAllCalls;

        public Department save(Department d) { store.removeIf(x -> x.id().equals(d.id())); store.add(d); return d; }
        public Optional<Department> findById(UUID id) { return store.stream().filter(d -> d.id().equals(id)).findFirst(); }
        public List<Department> findAll() {
            findAllCalls++;
            return store.stream().sorted(Comparator.comparing(Department::code)).toList();
        }
        public boolean existsByCode(String code) { return store.stream().anyMatch(d -> d.code().equals(code)); }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void getLanHaiKhongChamDbVaTreeCungVay() {
        Repo repo = new Repo();
        InMemoryRequestCache cache = new InMemoryRequestCache();
        CreateDepartmentUseCase create = new CachedDepartmentUseCases.EvictingCreate(repo, cache);
        ListDepartmentsUseCase list = new CachedDepartmentUseCases.CachedList(repo, cache);
        TenantContext.set(TENANT_A);
        DepartmentResponse root = create.execute(new CreateDepartmentRequest("ROOT", "Gốc", null));
        create.execute(new CreateDepartmentRequest("KID", "Con", root.id()));

        List<DepartmentResponse> first = list.list(null);
        int afterFirst = repo.findAllCalls;
        List<DepartmentResponse> second = list.list(null);
        assertThat(repo.findAllCalls).isEqualTo(afterFirst);
        assertThat(second).isEqualTo(first).hasSize(2);

        List<DepartmentNode> tree = list.tree(false);
        int afterTree = repo.findAllCalls;
        assertThat(list.tree(false)).isEqualTo(tree);
        assertThat(repo.findAllCalls).isEqualTo(afterTree);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).children()).hasSize(1);

        // tham số khác nhau là key khác nhau
        list.list(DepartmentStatus.INACTIVE);
        assertThat(repo.findAllCalls).isEqualTo(afterTree + 1);
    }

    @Test
    void ghiXongGetSauThayDuLieuMoi() {
        Repo repo = new Repo();
        InMemoryRequestCache cache = new InMemoryRequestCache();
        CreateDepartmentUseCase create = new CachedDepartmentUseCases.EvictingCreate(repo, cache);
        UpdateDepartmentUseCase update = new CachedDepartmentUseCases.EvictingUpdate(repo, cache);
        ChangeDepartmentStatusUseCase status = new CachedDepartmentUseCases.EvictingChangeStatus(repo, cache);
        ListDepartmentsUseCase list = new CachedDepartmentUseCases.CachedList(repo, cache);
        TenantContext.set(TENANT_A);

        DepartmentResponse a = create.execute(new CreateDepartmentRequest("A", "Phòng A", null));
        assertThat(list.list(null)).hasSize(1);
        assertThat(list.tree(false)).hasSize(1);

        create.execute(new CreateDepartmentRequest("B", "Phòng B", null));
        assertThat(list.list(null)).hasSize(2);
        assertThat(list.tree(false)).hasSize(2);

        update.execute(a.id(), new UpdateDepartmentRequest("Tên mới", null));
        assertThat(list.list(null)).extracting(DepartmentResponse::name).contains("Tên mới");

        status.execute(a.id(), DepartmentStatus.INACTIVE);
        assertThat(list.tree(false)).hasSize(1);
        assertThat(list.tree(true)).hasSize(2);
    }

    @Test
    void haiTenantKhongThayCacheCuaNhau() {
        Repo repoA = new Repo();
        Repo repoB = new Repo();
        InMemoryRequestCache cache = new InMemoryRequestCache();
        TenantContext.set(TENANT_A);
        new CachedDepartmentUseCases.EvictingCreate(repoA, cache)
                .execute(new CreateDepartmentRequest("A1", "Của A", null));
        ListDepartmentsUseCase listA = new CachedDepartmentUseCases.CachedList(repoA, cache);
        ListDepartmentsUseCase listB = new CachedDepartmentUseCases.CachedList(repoB, cache);
        assertThat(listA.list(null)).hasSize(1);

        TenantContext.set(TENANT_B);
        assertThat(listB.list(null)).isEmpty();

        TenantContext.set(TENANT_A);
        assertThat(listA.list(null)).hasSize(1);
    }

    @Test
    void khongCoTenantHoacCacheTatThiHanhViGiongCu() {
        Repo repo = new Repo();
        InMemoryRequestCache memory = new InMemoryRequestCache();
        ListDepartmentsUseCase noTenant = new CachedDepartmentUseCases.CachedList(repo, memory);
        noTenant.list(null);
        noTenant.list(null);
        assertThat(repo.findAllCalls).isEqualTo(2);

        TenantContext.bindAllTenants();
        noTenant.list(null);
        noTenant.list(null);
        assertThat(repo.findAllCalls).isEqualTo(4);

        TenantContext.set(TENANT_A);
        ListDepartmentsUseCase off = new CachedDepartmentUseCases.CachedList(repo, new NoOpRequestCache());
        off.list(null);
        off.list(null);
        assertThat(repo.findAllCalls).isEqualTo(6);
    }
}
