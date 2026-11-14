package lms.identity.user.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.DepartmentLookupPort;
import lms.identity.user.application.GetUserDirectoryInfoUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.UpdateUserRequest;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.application.UserResponse;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserManagementTest {

    final List<User> store = new ArrayList<>();
    final Set<UUID> activeDepartments = new HashSet<>();
    final UserRepository repo = new UserRepository() {
        public User save(User u) { store.removeIf(x -> x.id().equals(u.id())); store.add(u); return u; }
        public Optional<User> findById(UUID id) { return store.stream().filter(u -> u.id().equals(id)).findFirst(); }
        public boolean existsById(UUID id) { return store.stream().anyMatch(u -> u.id().equals(id)); }
        public boolean existsByUsername(String n) { return store.stream().anyMatch(u -> u.username().equals(n)); }
        public boolean existsByEmail(String e, UUID exclude) {
            return store.stream().anyMatch(u -> u.email().equalsIgnoreCase(e) && !u.id().equals(exclude));
        }
        public UserPage search(UserCriteria c) {
            List<User> hit = store.stream()
                    .filter(u -> c.keyword() == null || (u.username() + u.fullName() + u.email()).toLowerCase().contains(c.keyword().toLowerCase()))
                    .filter(u -> c.departmentId() == null || c.departmentId().equals(u.departmentId()))
                    .filter(u -> c.status() == null || c.status() == u.status()).toList();
            int from = Math.min(hit.size(), c.page() * c.size());
            return new UserPage(hit.subList(from, Math.min(hit.size(), from + c.size())), hit.size(), c.page(), c.size());
        }
    };
    final DepartmentLookupPort departments = activeDepartments::contains;
    CreateUserUseCase create;
    UpdateUserUseCase update;
    ChangeUserStatusUseCase status;

    @BeforeEach
    void setUp() {
        create = new CreateUserUseCase(repo, departments);
        update = new UpdateUserUseCase(repo, departments);
        status = new ChangeUserStatusUseCase(repo);
    }

    UserResponse make(String username, String email, UUID dept) {
        return create.execute(new CreateUserRequest(null, username, "Họ Tên " + username, email, dept));
    }

    @Test
    void taoNguoiDungChuanHoaTenDangNhapVaEmail() {
        UserResponse u = make("  Alice.Nguyen ", "Alice@School.TEST", null);
        assertEquals("alice.nguyen", u.username());
        assertEquals("alice@school.test", u.email());
        assertEquals(UserStatus.ACTIVE, u.status());
    }

    @Test
    void tuChoiTenDangNhapHoacEmailTrungVaDuLieuSai() {
        make("alice", "a@x.vn", null);
        assertThrows(DuplicateUserException.class, () -> make("ALICE", "b@x.vn", null));
        assertThrows(DuplicateUserException.class, () -> make("bob", "A@X.VN", null));
        assertThrows(InvalidUserException.class, () -> make("ab", "c@x.vn", null));
        assertThrows(InvalidUserException.class, () -> make("co dau cach", "c@x.vn", null));
        assertThrows(InvalidUserException.class, () -> make("carol", "khong-phai-email", null));
        assertEquals(1, store.size());
    }

    @Test
    void chiNhanIdCoSanKhiChuaTonTai() {
        UUID id = UUID.randomUUID();
        assertEquals(id, create.execute(new CreateUserRequest(id, "admin2", "Admin 2", "a2@x.vn", null)).id());
        assertThrows(DuplicateUserException.class,
                () -> create.execute(new CreateUserRequest(id, "other", "Other", "o@x.vn", null)));
    }

    @Test
    void phongBanPhaiTonTaiVaDangHoatDong() {
        UUID dept = UUID.randomUUID();
        assertThrows(InvalidUserException.class, () -> make("alice", "a@x.vn", dept));
        activeDepartments.add(dept);
        assertEquals(dept, make("alice", "a@x.vn", dept).departmentId());
    }

    @Test
    void capNhatKhongChoTrungEmailCuaNguoiKhacVaChoPhepGiuPhongBanDaNgung() {
        UUID dept = UUID.randomUUID();
        activeDepartments.add(dept);
        UserResponse alice = make("alice", "a@x.vn", dept);
        make("bob", "b@x.vn", null);

        assertThrows(DuplicateUserException.class,
                () -> update.execute(alice.id(), new UpdateUserRequest("Alice", "B@x.vn", dept)));
        assertEquals("a@x.vn", update.execute(alice.id(), new UpdateUserRequest("Alice mới", "a@x.vn", dept)).email());

        activeDepartments.clear(); // phòng ban bị ngừng: vẫn giữ nguyên được, nhưng không chuyển sang phòng ban ngừng khác
        assertEquals(dept, update.execute(alice.id(), new UpdateUserRequest("Alice", "a@x.vn", dept)).departmentId());
        assertThrows(InvalidUserException.class,
                () -> update.execute(alice.id(), new UpdateUserRequest("Alice", "a@x.vn", UUID.randomUUID())));
        assertThrows(UserNotFoundException.class,
                () -> update.execute(UUID.randomUUID(), new UpdateUserRequest("X", "x@x.vn", null)));
    }

    @Test
    void voHieuHoaKhongTuKhoaMinhVaThongTinChoAuthPhanAnhTrangThai() {
        UserResponse alice = make("alice", "a@x.vn", null);
        UserResponse admin = make("admin", "ad@x.vn", null);
        GetUserDirectoryInfoUseCase directory = new GetUserDirectoryInfoUseCase(repo);

        assertThrows(InvalidUserException.class, () -> status.execute(admin.id(), admin.id(), UserStatus.DISABLED));
        assertFalse(directory.execute(alice.id()).orElseThrow().disabled());

        status.execute(admin.id(), alice.id(), UserStatus.DISABLED);
        assertTrue(directory.execute(alice.id()).orElseThrow().disabled());
        status.execute(admin.id(), alice.id(), UserStatus.ACTIVE);
        assertFalse(directory.execute(alice.id()).orElseThrow().disabled());
        assertTrue(directory.execute(UUID.randomUUID()).isEmpty());
    }

    @Test
    void timKiemLocPhanTrangVaGioiHanKichThuoc() {
        UUID dept = UUID.randomUUID();
        activeDepartments.add(dept);
        for (int i = 0; i < 5; i++) make("user" + i, "u" + i + "@x.vn", i % 2 == 0 ? dept : null);
        status.execute(UUID.randomUUID(), store.get(0).id(), UserStatus.DISABLED);
        SearchUsersUseCase search = new SearchUsersUseCase(repo);

        assertEquals(5, search.execute(null, null, null, 0, 20).total());
        assertEquals(3, search.execute(null, dept, null, 0, 20).total());
        assertEquals(1, search.execute(null, null, UserStatus.DISABLED, 0, 20).total());
        assertEquals(1, search.execute("USER3", null, null, 0, 20).total());
        var page2 = search.execute("  ", null, null, 1, 2);
        assertEquals(2, page2.items().size());
        assertEquals(5, page2.total());
        assertEquals(100, search.execute(null, null, null, -5, 100000).size());
        assertEquals(1, search.execute(null, null, null, 0, 0).size());
    }
}
