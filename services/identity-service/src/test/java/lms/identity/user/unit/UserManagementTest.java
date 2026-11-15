package lms.identity.user.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lms.identity.user.application.AccountCredentialPort;
import lms.identity.user.application.ChangeUserStatusUseCase;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.application.DepartmentLookupPort;
import lms.identity.user.application.GetUserDirectoryInfoUseCase;
import lms.identity.user.application.GetUserUseCase;
import lms.identity.user.application.IssueReaderCardCommand;
import lms.identity.user.application.IssueReaderCardUseCase;
import lms.identity.user.application.ResetUserPasswordUseCase;
import lms.identity.user.application.SearchUsersUseCase;
import lms.identity.user.application.TenantMembershipPort;
import lms.identity.user.application.UpdateUserRequest;
import lms.identity.user.application.UpdateUserUseCase;
import lms.identity.user.application.UserResponse;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderProfileRepository;
import lms.identity.user.domain.ReaderType;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Quản lý người dùng theo tenant (spec 5.6): bộ nhớ giả lập users (toàn cục), tenant_memberships (kèm phòng ban) và
 * credentials. Tenant hiện tại đặt bằng TenantContext.
 */
class UserManagementTest {

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID adminA = UUID.randomUUID();

    final List<User> store = new ArrayList<>();
    /** userId -> (tenantId -> departmentId hoặc null); dùng HashMap để giữ được giá trị null. */
    final Map<UUID, Map<UUID, UUID>> memberships = new HashMap<>();
    /** credentials: userId -> username */
    final Map<UUID, String> credentialUsernames = new HashMap<>();
    final Set<UUID> platformAdmins = new HashSet<>();
    final Map<UUID, String> resetPasswords = new HashMap<>();
    final Set<UUID> activeDepartments = new HashSet<>();

    final UserRepository repo = new UserRepository() {
        public User save(User u) { store.removeIf(x -> x.id().equals(u.id())); store.add(u); return u; }
        public Optional<User> findById(UUID id) { return store.stream().filter(u -> u.id().equals(id)).findFirst(); }
        public Optional<TenantUser> findInTenant(UUID id, UUID tenantId) {
            if (!hasMembership(id, tenantId)) return Optional.empty();
            return findById(id).map(u -> new TenantUser(u, tenantId, memberships.get(id).get(tenantId)));
        }
        public boolean existsById(UUID id) { return store.stream().anyMatch(u -> u.id().equals(id)); }
        public boolean existsByUsername(String n) { return store.stream().anyMatch(u -> u.username().equals(n)); }
        public boolean existsByEmail(String e, UUID exclude) {
            return store.stream().anyMatch(u -> u.email().equalsIgnoreCase(e) && !u.id().equals(exclude));
        }
        public boolean hasMembership(UUID userId, UUID tenantId) {
            return memberships.getOrDefault(userId, Map.of()).containsKey(tenantId);
        }
        public int countMembershipsOf(UUID userId) { return memberships.getOrDefault(userId, Map.of()).size(); }
        public UserPage search(UserCriteria c) {
            List<TenantUser> hit = store.stream()
                    .filter(u -> hasMembership(u.id(), c.tenantId()))
                    .map(u -> new TenantUser(u, c.tenantId(), memberships.get(u.id()).get(c.tenantId())))
                    .filter(t -> c.keyword() == null || (t.user().username() + t.user().fullName() + t.user().email())
                            .toLowerCase().contains(c.keyword().toLowerCase()))
                    .filter(t -> c.departmentId() == null || c.departmentId().equals(t.departmentId()))
                    .filter(t -> c.status() == null || c.status() == t.user().status()).toList();
            int from = Math.min(hit.size(), c.page() * c.size());
            return new UserPage(hit.subList(from, Math.min(hit.size(), from + c.size())), hit.size(), c.page(), c.size());
        }
    };
    final DepartmentLookupPort departments = activeDepartments::contains;
    final TenantMembershipPort membershipPort = new TenantMembershipPort() {
        public void join(UUID userId, UUID tenantId, UUID departmentId) {
            Map<UUID, UUID> of = memberships.computeIfAbsent(userId, k -> new HashMap<>());
            if (!of.containsKey(tenantId) || departmentId != null) of.put(tenantId, departmentId);
        }
        public void changeDepartment(UUID userId, UUID tenantId, UUID departmentId) {
            memberships.get(userId).put(tenantId, departmentId);
        }
    };
    final AccountCredentialPort credentials = new AccountCredentialPort() {
        public Optional<UUID> findUserIdByUsername(String username) {
            return credentialUsernames.entrySet().stream().filter(e -> e.getValue().equals(username))
                    .map(Map.Entry::getKey).findFirst();
        }
        public boolean exists(UUID userId) { return credentialUsernames.containsKey(userId); }
        public boolean isPlatformAdmin(UUID userId) { return platformAdmins.contains(userId); }
        public void register(UUID userId, String username, String rawPassword, boolean mustChangePassword) {
            credentialUsernames.put(userId, username);
        }
        public boolean resetToTemporaryPassword(UUID userId, String temporaryPassword) {
            if (!credentialUsernames.containsKey(userId)) return false;
            resetPasswords.put(userId, temporaryPassword);
            return true;
        }
    };
    CreateUserUseCase create;
    UpdateUserUseCase update;
    ChangeUserStatusUseCase status;
    ResetUserPasswordUseCase reset;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantA);
        create = new CreateUserUseCase(repo, departments, credentials, membershipPort);
        update = new UpdateUserUseCase(repo, departments, credentials, membershipPort);
        status = new ChangeUserStatusUseCase(repo, credentials);
        reset = new ResetUserPasswordUseCase(repo, credentials);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    UserResponse make(String username, String email, UUID dept) {
        return create.execute(new CreateUserRequest(null, username, "Họ Tên " + username, email, dept));
    }

    UserResponse makeWithPassword(String username, String email, UUID dept) {
        return create.execute(new CreateUserRequest(null, username, "Họ Tên " + username, email, dept,
                "Password@1", true));
    }

    /** Tạo người dùng ở tenant B (chạy trong ngữ cảnh tenant B rồi trả về tenant A). */
    UserResponse makeInB(String username, String email) {
        return TenantContext.callAs(tenantB, () -> makeWithPassword(username, email, null));
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

    // ---- tạo người dùng trong tenant hiện tại (credential + users + membership), m1 ----

    @Test
    void taoNguoiDungCoMatKhauTaoCredentialVaMembershipKemPhongBanTrongTenantHienTai() {
        UUID dept = UUID.randomUUID();
        activeDepartments.add(dept);
        UserResponse u = makeWithPassword("dora", "dora@x.vn", dept);

        assertEquals("dora", credentialUsernames.get(u.id()));
        assertEquals(Map.of(tenantA, dept), memberships.get(u.id()));
        assertEquals(dept, u.departmentId());
    }

    @Test
    void ganHoSoVaoCredentialCoSanGhiPhongBanVaoMembershipCuaTenant() {
        UUID dept = UUID.randomUUID();
        activeDepartments.add(dept);
        UUID id = UUID.randomUUID();
        credentialUsernames.put(id, "erin"); // credential tạo trước bởi tenant A (đã có membership, chưa có phòng ban)
        memberships.computeIfAbsent(id, k -> new HashMap<>()).put(tenantA, null);

        UserResponse u = create.execute(new CreateUserRequest(id, "erin", "Erin", "erin@x.vn", dept));

        assertEquals(dept, u.departmentId());
        assertEquals(dept, memberships.get(id).get(tenantA));
    }

    @Test
    void trungUsernameHoacEmailOTenantKhacCungLoiVaThongDiepNhuTrungTrongTenant() {
        // Review Focus 4: không lộ tài khoản thuộc tenant nào
        make("alice", "a@x.vn", null);
        makeInB("bob", "b@x.vn");
        UUID credentialOnlyInB = UUID.randomUUID();
        credentialUsernames.put(credentialOnlyInB, "carol");
        memberships.computeIfAbsent(credentialOnlyInB, k -> new HashMap<>()).put(tenantB, null);

        DuplicateUserException sameTenant = assertThrows(DuplicateUserException.class,
                () -> makeWithPassword("alice", "new1@x.vn", null));
        DuplicateUserException otherTenantUsername = assertThrows(DuplicateUserException.class,
                () -> makeWithPassword("bob", "new2@x.vn", null));
        DuplicateUserException otherTenantEmail = assertThrows(DuplicateUserException.class,
                () -> makeWithPassword("bobby", "B@x.vn", null));
        DuplicateUserException credentialOnly = assertThrows(DuplicateUserException.class,
                () -> makeWithPassword("carol", "carol@x.vn", null));
        // gắn hồ sơ vào credential của tenant khác: cũng chỉ là "đã tồn tại"
        DuplicateUserException attachForeign = assertThrows(DuplicateUserException.class,
                () -> create.execute(new CreateUserRequest(credentialOnlyInB, "carol", "Carol", "c2@x.vn", null)));

        for (DuplicateUserException e : List.of(otherTenantUsername, otherTenantEmail, credentialOnly, attachForeign)) {
            assertEquals(sameTenant.getMessage(), e.getMessage());
        }
        assertEquals(DuplicateUserException.GENERIC_MESSAGE, sameTenant.getMessage());
        assertFalse(memberships.get(credentialOnlyInB).containsKey(tenantA));
    }

    // ---- sửa, cách ly theo tenant ----

    @Test
    void capNhatKhongChoTrungEmailCuaNguoiKhacVaChoPhepGiuPhongBanDaNgung() {
        UUID dept = UUID.randomUUID();
        activeDepartments.add(dept);
        UserResponse alice = make("alice", "a@x.vn", dept);
        make("bob", "b@x.vn", null);

        assertThrows(DuplicateUserException.class,
                () -> update.execute(adminA, alice.id(), new UpdateUserRequest("Alice", "B@x.vn", dept)));
        assertEquals("a@x.vn", update.execute(adminA, alice.id(), new UpdateUserRequest("Alice mới", "a@x.vn", dept)).email());

        activeDepartments.clear(); // phòng ban bị ngừng: vẫn giữ nguyên được, nhưng không chuyển sang phòng ban ngừng khác
        assertEquals(dept, update.execute(adminA, alice.id(), new UpdateUserRequest("Alice", "a@x.vn", dept)).departmentId());
        assertThrows(InvalidUserException.class,
                () -> update.execute(adminA, alice.id(), new UpdateUserRequest("Alice", "a@x.vn", UUID.randomUUID())));
        assertThrows(UserNotFoundException.class,
                () -> update.execute(adminA, UUID.randomUUID(), new UpdateUserRequest("X", "x@x.vn", null)));
    }

    @Test
    void doiPhongBanGhiVaoMembershipCuaTenantHienTai() {
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        activeDepartments.addAll(List.of(d1, d2));
        UserResponse alice = makeWithPassword("alice", "a@x.vn", d1);

        update.execute(adminA, alice.id(), new UpdateUserRequest(alice.fullName(), alice.email(), d2));
        assertEquals(d2, memberships.get(alice.id()).get(tenantA));
        update.execute(adminA, alice.id(), new UpdateUserRequest(alice.fullName(), alice.email(), null));
        assertNull(memberships.get(alice.id()).get(tenantA));
    }

    @Test
    void nguoiChiOTenantKhacKhongDocSuaDatLaiMatKhauHayDoiTrangThaiDuoc() {
        UserResponse onlyB = makeInB("bob", "b@x.vn");
        GetUserUseCase get = new GetUserUseCase(repo);

        assertThrows(UserNotFoundException.class, () -> get.execute(onlyB.id()));
        assertThrows(UserNotFoundException.class,
                () -> update.execute(adminA, onlyB.id(), new UpdateUserRequest("Bob mới", "b@x.vn", null)));
        assertThrows(UserNotFoundException.class, () -> reset.execute(onlyB.id(), "TempPass@1"));
        assertThrows(UserNotFoundException.class, () -> status.execute(adminA, onlyB.id(), UserStatus.DISABLED));
        assertEquals(0, new SearchUsersUseCase(repo).execute("bob", null, null, 0, 20).total());
        assertTrue(resetPasswords.isEmpty());
        assertEquals("Họ Tên bob", store.stream().filter(u -> u.id().equals(onlyB.id())).findFirst().orElseThrow().fullName());
    }

    @Test
    void nguoiThuocHaiTenantKhongSuaHoTenVaKhongDatLaiMatKhauDuocConNguoiChiThuocADuoc() {
        // Review Focus 5
        UserResponse shared = makeWithPassword("shared", "s@x.vn", null);
        memberships.get(shared.id()).put(tenantB, null);
        UserResponse onlyA = makeWithPassword("onlya", "oa@x.vn", null);

        assertThrows(UserOperationNotAllowedException.class,
                () -> update.execute(adminA, shared.id(), new UpdateUserRequest("Tên mới", "s@x.vn", null)));
        assertThrows(UserOperationNotAllowedException.class,
                () -> update.execute(adminA, shared.id(), new UpdateUserRequest(shared.fullName(), "moi@x.vn", null)));
        assertThrows(UserOperationNotAllowedException.class, () -> reset.execute(shared.id(), "TempPass@1"));
        assertThrows(UserOperationNotAllowedException.class,
                () -> status.execute(adminA, shared.id(), UserStatus.DISABLED));
        assertFalse(resetPasswords.containsKey(shared.id()));

        // phòng ban là dữ liệu của tenant: vẫn đổi được cho người thuộc nhiều tenant
        UUID dept = UUID.randomUUID();
        activeDepartments.add(dept);
        assertEquals(dept, update.execute(adminA, shared.id(),
                new UpdateUserRequest(shared.fullName(), shared.email(), dept)).departmentId());
        // chính người dùng thì tự sửa được hồ sơ của mình
        assertEquals("Tự sửa", update.execute(shared.id(), shared.id(),
                new UpdateUserRequest("Tự sửa", shared.email(), dept)).fullName());

        assertEquals("Tên mới", update.execute(adminA, onlyA.id(),
                new UpdateUserRequest("Tên mới", "oa@x.vn", null)).fullName());
        reset.execute(onlyA.id(), "TempPass@1");
        assertEquals("TempPass@1", resetPasswords.get(onlyA.id()));
    }

    @Test
    void khongSuaHayDatLaiMatKhauPlatformAdmin() {
        UserResponse operator = makeWithPassword("operator", "op@x.vn", null);
        platformAdmins.add(operator.id());

        assertThrows(UserOperationNotAllowedException.class,
                () -> update.execute(adminA, operator.id(), new UpdateUserRequest("Khác", "op@x.vn", null)));
        assertThrows(UserOperationNotAllowedException.class, () -> reset.execute(operator.id(), "TempPass@1"));
    }

    @Test
    void datLaiMatKhauCanMatKhauDuDaiVaTaiKhoanDangNhap() {
        UserResponse profileOnly = make("noaccount", "na@x.vn", null);
        assertThrows(InvalidUserException.class, () -> reset.execute(profileOnly.id(), "TempPass@1"));
        UserResponse withLogin = makeWithPassword("haslogin", "hl@x.vn", null);
        assertThrows(InvalidUserException.class, () -> reset.execute(withLogin.id(), "short"));
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
        makeInB("userb", "ub@x.vn"); // người của tenant khác không lọt vào kết quả
        status.execute(UUID.randomUUID(), store.get(0).id(), UserStatus.DISABLED);
        SearchUsersUseCase search = new SearchUsersUseCase(repo);

        assertEquals(5, search.execute(null, null, null, 0, 20).total());
        assertEquals(3, search.execute(null, dept, null, 0, 20).total());
        assertEquals(1, search.execute(null, null, UserStatus.DISABLED, 0, 20).total());
        assertEquals(1, search.execute("USER3", null, null, 0, 20).total());
        assertEquals(0, search.execute("userb", null, null, 0, 20).total());
        var page2 = search.execute("  ", null, null, 1, 2);
        assertEquals(2, page2.items().size());
        assertEquals(5, page2.total());
        assertEquals(100, search.execute(null, null, null, -5, 100000).size());
        assertEquals(1, search.execute(null, null, null, 0, 0).size());
    }

    // ---- cấp thẻ độc giả kèm tài khoản online ----

    @Test
    void capTheKemTaiKhoanOnlineTaoCredentialMatKhauTamVaMembershipTrongTenant() {
        List<ReaderProfile> profiles = new ArrayList<>();
        ReaderProfileRepository readers = new ReaderProfileRepository() {
            public ReaderProfile save(ReaderProfile p) { profiles.add(p); return p; }
            public Optional<ReaderProfile> findByUserId(UUID u) {
                return profiles.stream().filter(p -> p.userId().equals(u)).findFirst();
            }
            public boolean existsByUserId(UUID u) { return findByUserId(u).isPresent(); }
            public boolean existsByCardNo(String c) { return profiles.stream().anyMatch(p -> p.cardNo().equals(c)); }
            public List<UUID> findUserIdsByEmail(String e) { return List.of(); }
        };
        Clock clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
        IssueReaderCardUseCase issue = new IssueReaderCardUseCase(readers, clock, repo, credentials, create);
        LocalDate expiry = LocalDate.of(2027, 11, 15);

        UUID reader = UUID.randomUUID();
        issue.execute(new IssueReaderCardCommand(reader, "SV001", "Độc Giả", "dg@x.vn", ReaderType.STUDENT, expiry,
                null, "TempPass@1"));
        assertEquals("sv001", credentialUsernames.get(reader));
        assertTrue(memberships.get(reader).containsKey(tenantA));
        assertEquals(1, profiles.size());

        // tài khoản của tenant khác (không là thành viên tenant A): không cấp thẻ, như không tồn tại
        UserResponse onlyB = makeInB("bob", "b@x.vn");
        assertThrows(UserNotFoundException.class, () -> issue.execute(new IssueReaderCardCommand(onlyB.id(), "SV002",
                "Bob", "b@x.vn", ReaderType.STUDENT, expiry)));
        // độc giả chỉ dùng tại quầy (chưa có tài khoản): vẫn cấp được
        issue.execute(new IssueReaderCardCommand(UUID.randomUUID(), "SV003", "Quầy", "q@x.vn", ReaderType.STUDENT, expiry));
        assertEquals(2, profiles.size());
    }
}
