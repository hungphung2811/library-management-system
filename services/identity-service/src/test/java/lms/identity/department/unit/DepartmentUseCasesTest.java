package lms.identity.department.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.application.CreateDepartmentRequest;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.DepartmentNode;
import lms.identity.department.application.DepartmentResponse;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentRequest;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.identity.department.domain.Department;
import lms.identity.department.domain.DepartmentNotFoundException;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.DepartmentStatus;
import lms.identity.department.domain.DuplicateDepartmentException;
import lms.identity.department.domain.InvalidDepartmentException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DepartmentUseCasesTest {

    final List<Department> store = new ArrayList<>();
    final DepartmentRepository repo = new DepartmentRepository() {
        public Department save(Department d) { store.removeIf(x -> x.id().equals(d.id())); store.add(d); return d; }
        public Optional<Department> findById(UUID id) { return store.stream().filter(d -> d.id().equals(id)).findFirst(); }
        public List<Department> findAll() { return store.stream().sorted(Comparator.comparing(Department::code)).toList(); }
        public boolean existsByCode(String code) { return store.stream().anyMatch(d -> d.code().equals(code)); }
    };
    CreateDepartmentUseCase create;
    UpdateDepartmentUseCase update;
    ChangeDepartmentStatusUseCase status;
    ListDepartmentsUseCase list;

    @BeforeEach
    void setUp() {
        create = new CreateDepartmentUseCase(repo);
        update = new UpdateDepartmentUseCase(repo);
        status = new ChangeDepartmentStatusUseCase(repo);
        list = new ListDepartmentsUseCase(repo);
    }

    DepartmentResponse make(String code, UUID parent) {
        return create.execute(new CreateDepartmentRequest(code, "Phòng " + code, parent));
    }

    @Test
    void taoPhongBanChuanHoaMaVaTuChoiMaTrungHoacMaSai() {
        DepartmentResponse it = make("it-dept", null);
        assertEquals("IT-DEPT", it.code());
        assertEquals(DepartmentStatus.ACTIVE, it.status());
        assertThrows(DuplicateDepartmentException.class, () -> make("IT-DEPT", null));
        assertThrows(InvalidDepartmentException.class, () -> make("co dau cach", null));
        assertThrows(InvalidDepartmentException.class, () -> create.execute(new CreateDepartmentRequest("A", " ", null)));
    }

    @Test
    void phongBanChaPhaiTonTaiVaDangHoatDong() {
        assertThrows(DepartmentNotFoundException.class, () -> make("A", UUID.randomUUID()));
        DepartmentResponse root = make("ROOT", null);
        status.execute(root.id(), DepartmentStatus.INACTIVE);
        assertThrows(InvalidDepartmentException.class, () -> make("CHILD", root.id()));
    }

    @Test
    void chuyenPhongBanKhongTaoChuTrinh() {
        DepartmentResponse a = make("A", null);
        DepartmentResponse b = make("B", a.id());
        DepartmentResponse c = make("C", b.id());

        assertThrows(InvalidDepartmentException.class, () -> update.execute(a.id(), new UpdateDepartmentRequest("A", c.id())));
        assertThrows(InvalidDepartmentException.class, () -> update.execute(a.id(), new UpdateDepartmentRequest("A", a.id())));
        assertThrows(InvalidDepartmentException.class, () -> update.execute(b.id(), new UpdateDepartmentRequest("B", c.id())));

        // chuyển hợp lệ: C lên làm con trực tiếp của A, rồi lên gốc
        assertEquals(a.id(), update.execute(c.id(), new UpdateDepartmentRequest("C mới", a.id())).parentId());
        DepartmentResponse moved = update.execute(c.id(), new UpdateDepartmentRequest("C mới", null));
        assertEquals(null, moved.parentId());
        assertEquals("C mới", moved.name());
    }

    @Test
    void ngungChiKhiHetPhongBanConDangHoatDongVaKichHoatLaiChiKhiChaHoatDong() {
        DepartmentResponse a = make("A", null);
        DepartmentResponse b = make("B", a.id());

        assertThrows(InvalidDepartmentException.class, () -> status.execute(a.id(), DepartmentStatus.INACTIVE));
        status.execute(b.id(), DepartmentStatus.INACTIVE);
        status.execute(a.id(), DepartmentStatus.INACTIVE);
        assertThrows(InvalidDepartmentException.class, () -> status.execute(b.id(), DepartmentStatus.ACTIVE));
        status.execute(a.id(), DepartmentStatus.ACTIVE);
        assertEquals(DepartmentStatus.ACTIVE, status.execute(b.id(), DepartmentStatus.ACTIVE).status());
    }

    @Test
    void khongChuyenPhongBanDangHoatDongVaoPhongBanDaNgung() {
        DepartmentResponse a = make("A", null);
        DepartmentResponse x = make("X", null);
        status.execute(x.id(), DepartmentStatus.INACTIVE);
        assertThrows(InvalidDepartmentException.class, () -> update.execute(a.id(), new UpdateDepartmentRequest("A", x.id())));
    }

    @Test
    void cayVaDanhSachLocTheoTrangThai() {
        DepartmentResponse a = make("A", null);
        DepartmentResponse b = make("B", a.id());
        make("C", a.id());
        DepartmentResponse d = make("D", null);
        status.execute(b.id(), DepartmentStatus.INACTIVE);
        status.execute(d.id(), DepartmentStatus.INACTIVE);

        List<DepartmentNode> active = list.tree(false);
        assertEquals(1, active.size());
        assertEquals("A", active.get(0).code());
        assertEquals(List.of("C"), active.get(0).children().stream().map(DepartmentNode::code).toList());

        List<DepartmentNode> all = list.tree(true);
        assertEquals(List.of("A", "D"), all.stream().map(DepartmentNode::code).toList());
        assertEquals(List.of("B", "C"), all.get(0).children().stream().map(DepartmentNode::code).toList());

        assertEquals(2, list.list(DepartmentStatus.INACTIVE).size());
        assertEquals(4, list.list(null).size());
        assertTrue(list.list(DepartmentStatus.ACTIVE).stream().noneMatch(x -> x.status() == DepartmentStatus.INACTIVE));
        assertFalse(store.isEmpty());
    }

    /**
     * Mỗi tenant chỉ thấy kho phòng ban của mình (lớp lưu trữ lọc bằng @TenantId + RLS): giả lập bằng hai kho riêng.
     * Mã phòng ban chỉ unique trong tenant; phòng ban của tenant khác không dùng làm phòng ban cha được.
     */
    @Test
    void maPhongBanUniqueTheoTenantVaKhongDungPhongBanTenantKhacLamCha() {
        List<Department> otherStore = new ArrayList<>();
        DepartmentRepository otherTenant = new DepartmentRepository() {
            public Department save(Department d) { otherStore.removeIf(x -> x.id().equals(d.id())); otherStore.add(d); return d; }
            public Optional<Department> findById(UUID id) { return otherStore.stream().filter(d -> d.id().equals(id)).findFirst(); }
            public List<Department> findAll() { return List.copyOf(otherStore); }
            public boolean existsByCode(String code) { return otherStore.stream().anyMatch(d -> d.code().equals(code)); }
        };
        CreateDepartmentUseCase createInOther = new CreateDepartmentUseCase(otherTenant);

        DepartmentResponse mine = make("IT", null);
        DepartmentResponse theirs = createInOther.execute(new CreateDepartmentRequest("IT", "Phòng IT B", null));
        assertEquals(mine.code(), theirs.code());
        assertThrows(DuplicateDepartmentException.class, () -> make("IT", null));
        assertThrows(DepartmentNotFoundException.class,
                () -> createInOther.execute(new CreateDepartmentRequest("CON", "Con", mine.id())));
        assertEquals(1, new ListDepartmentsUseCase(otherTenant).list(null).size());
    }
}
