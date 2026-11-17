package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.common.kernel.DomainEvent;
import lms.identity.tenant.application.AddTenantAdminUseCase;
import lms.identity.tenant.application.AttachUserRequest;
import lms.identity.tenant.application.AttachUserToTenantUseCase;
import lms.identity.tenant.application.CreateTenantRequest;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.GetTenantUseCase;
import lms.identity.tenant.application.ManageOrganizationUseCase;
import lms.identity.tenant.application.NewAdmin;
import lms.identity.tenant.application.OrganizationRequest;
import lms.identity.tenant.application.OrganizationResponse;
import lms.identity.tenant.application.PageResponse;
import lms.identity.tenant.application.PlatformAccountException;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.RemoveTenantAdminUseCase;
import lms.identity.tenant.application.SearchTenantsUseCase;
import lms.identity.tenant.application.SetTenantStatusUseCase;
import lms.identity.tenant.application.TenantAccountPort;
import lms.identity.tenant.application.TenantAdminRequest;
import lms.identity.tenant.application.TenantDetailResponse;
import lms.identity.tenant.application.TenantResponse;
import lms.identity.tenant.application.TenantRoleProvisioningPort;
import lms.identity.tenant.application.TenantSearchRequest;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.tenant.application.UpdateTenantRequest;
import lms.identity.tenant.application.UpdateTenantUseCase;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.InvalidTenantException;
import lms.identity.tenant.domain.Membership;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.MembershipStatus;
import lms.identity.tenant.domain.Organization;
import lms.identity.tenant.domain.OrganizationNotFoundException;
import lms.identity.tenant.domain.OrganizationRepository;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantCreatedEvent;
import lms.identity.tenant.domain.TenantCriteria;
import lms.identity.tenant.domain.TenantPage;
import lms.identity.tenant.domain.TenantQueryRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;
import lms.identity.tenant.domain.TenantStatusChangedEvent;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Use case quản lý tenant của người vận hành (spec §7): tạo tenant + role mẫu + quản trị viên, trạng thái, audit. */
class PlatformTenantUseCasesTest {

    private static final List<String> TEMPLATES = List.of("SUPER_ADMIN", "LIBRARIAN", "MEMBER", "READER", "AUDITOR");

    private final FakeTenants tenants = new FakeTenants();
    private final FakeOrganizations organizations = new FakeOrganizations();
    private final FakeMemberships memberships = new FakeMemberships();
    private final FakeRoles roles = new FakeRoles();
    private final FakeAccounts accounts = new FakeAccounts(memberships);
    private final RecordingEvents events = new RecordingEvents();
    private final TransactionalRunner tx = new TransactionalRunner();

    private final InMemoryTenantModules modules = new InMemoryTenantModules();

    private final CreateTenantUseCase create = new CreateTenantUseCase(tenants, organizations, memberships, roles,
            accounts, events, tx, modules);
    private final UpdateTenantUseCase update = new UpdateTenantUseCase(tenants, organizations, tenants, events, tx,
            modules);
    private final SetTenantStatusUseCase status = new SetTenantStatusUseCase(tenants, tenants, events, tx, modules);
    private final SearchTenantsUseCase search = new SearchTenantsUseCase(tenants, modules);
    private final GetTenantUseCase get = new GetTenantUseCase(tenants, tenants, roles, tx, modules);
    private final ManageOrganizationUseCase orgs = new ManageOrganizationUseCase(organizations);
    private final AddTenantAdminUseCase addAdmin = new AddTenantAdminUseCase(tenants, memberships, roles, accounts,
            events, tx);
    private final RemoveTenantAdminUseCase removeAdmin = new RemoveTenantAdminUseCase(tenants, roles, events, tx);
    private final AttachUserToTenantUseCase attach = new AttachUserToTenantUseCase(tenants, memberships, accounts,
            events, tx);

    private final UUID operator = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static NewAdmin newAdmin(String username) {
        return new NewAdmin(username, "Quản trị " + username, username + "@lib.vn", "Temp@12345");
    }

    private TenantResponse createLib(String code) {
        return create.execute(new CreateTenantRequest(code, "Thư viện " + code, null, newAdmin(code + "-admin"), null),
                operator);
    }

    private long auditCount() {
        return events.published.stream().filter(e -> e.event() instanceof PlatformOperationEvent).count();
    }

    @Test
    void taoTenantTaoDu5RoleTuMauVaQuanTriVienDauTienCoRoleAdmin() {
        TenantResponse created = createLib("lib-a");

        assertThat(created.code()).isEqualTo("lib-a");
        assertThat(created.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(created.memberCount()).isEqualTo(1);
        assertThat(tenants.byId).containsKey(created.id());
        // đủ 5 role từ mẫu, đúng một role quản trị, tất cả thuộc tenant mới
        assertThat(roles.rolesOf(created.id())).extracting(FakeRole::name)
                .containsExactlyInAnyOrderElementsOf(TEMPLATES);
        assertThat(roles.rolesOf(created.id())).filteredOn(FakeRole::admin).extracting(FakeRole::name)
                .containsExactly("SUPER_ADMIN");
        // quản trị viên đầu tiên: tài khoản mới, là thành viên ACTIVE và có role admin của tenant
        UUID adminId = accounts.byUsername.get("lib-a-admin");
        assertThat(adminId).isNotNull();
        assertThat(roles.adminsOf(created.id())).containsExactly(adminId);
        assertThat(memberships.find(adminId, created.id())).hasValueSatisfying(m -> assertThat(m.isActive()).isTrue());
        // seed/gán role chạy trong TenantContext của tenant mới (R6), xong thì khôi phục ngữ cảnh
        assertThat(roles.seededInContext).containsExactly(created.id());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void taoTenantPhatTenantCreatedVaAuditVoiEnvelopeCuaTenantMoi() {
        TenantResponse created = createLib("lib-ev");

        assertThat(events.published).filteredOn(e -> e.event() instanceof TenantCreatedEvent).singleElement()
                .satisfies(e -> {
                    assertThat(e.event().eventType()).isEqualTo("TenantCreated");
                    assertThat(((TenantCreatedEvent) e.event()).tenantId()).isEqualTo(created.id());
                    assertThat(e.envelopeTenant()).isEqualTo(created.id());
                });
        assertThat(auditCount()).isEqualTo(1);
    }

    @Test
    void taoTenantGanTaiKhoanCoSanLamQuanTriVien() {
        UUID existing = accounts.existing("san-co");
        TenantResponse created = create.execute(new CreateTenantRequest("lib-b", "Thư viện B", null, null, existing),
                operator);

        assertThat(roles.adminsOf(created.id())).containsExactly(existing);
        assertThat(memberships.find(existing, created.id())).isPresent();
    }

    @Test
    void codeTrungBiDuplicateTenantExceptionVaKhongTaoGiThem() {
        createLib("lib-dup");
        int eventsBefore = events.published.size();

        assertThatThrownBy(() -> create.execute(new CreateTenantRequest("lib-dup", "Khác", null,
                newAdmin("other-admin"), null), operator)).isInstanceOf(DuplicateTenantException.class);
        assertThat(tenants.byId).hasSize(1);
        assertThat(accounts.byUsername).doesNotContainKey("other-admin");
        assertThat(events.published).hasSize(eventsBefore);
    }

    @Test
    void canDungMotTrongNewAdminHoacExistingUserId() {
        assertThatThrownBy(() -> create.execute(new CreateTenantRequest("lib-x", "X", null, null, null), operator))
                .isInstanceOf(InvalidTenantException.class);
        assertThatThrownBy(() -> create.execute(new CreateTenantRequest("lib-y", "Y", null, newAdmin("y-admin"),
                UUID.randomUUID()), operator)).isInstanceOf(InvalidTenantException.class);
        assertThat(tenants.byId).isEmpty();
    }

    @Test
    void taiKhoanCoSanKhongTonTaiHoacTenDangNhapTrungBiTuChoi() {
        assertThatThrownBy(() -> create.execute(new CreateTenantRequest("lib-z", "Z", null, null, UUID.randomUUID()),
                operator)).isInstanceOf(PlatformAccountException.class)
                .extracting(e -> ((PlatformAccountException) e).reason())
                .isEqualTo(PlatformAccountException.Reason.NOT_FOUND);

        createLib("lib-u");
        assertThatThrownBy(() -> create.execute(new CreateTenantRequest("lib-u2", "U2", null, newAdmin("lib-u-admin"),
                null), operator)).isInstanceOf(PlatformAccountException.class)
                .extracting(e -> ((PlatformAccountException) e).reason())
                .isEqualTo(PlatformAccountException.Reason.DUPLICATE);
    }

    @Test
    void organizationKhongTonTaiBiTuChoi() {
        assertThatThrownBy(() -> create.execute(new CreateTenantRequest("lib-o", "O", UUID.randomUUID(),
                newAdmin("o-admin"), null), operator)).isInstanceOf(OrganizationNotFoundException.class);
    }

    @Test
    void suspendRoiActivatePhatTenantStatusChangedVaAudit() {
        TenantResponse created = createLib("lib-s");
        events.published.clear();

        assertThat(status.suspend(created.id(), operator).status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(tenants.byId.get(created.id()).status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(status.activate(created.id(), operator).status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenants.byId.get(created.id()).isActive()).isTrue();

        List<Recorded> changes = events.published.stream()
                .filter(e -> e.event() instanceof TenantStatusChangedEvent).toList();
        assertThat(changes).extracting(e -> ((TenantStatusChangedEvent) e.event()).status())
                .containsExactly(TenantStatus.SUSPENDED, TenantStatus.ACTIVE);
        assertThat(changes).allSatisfy(e -> {
            assertThat(e.event().eventType()).isEqualTo("TenantStatusChanged");
            assertThat(e.envelopeTenant()).isEqualTo(created.id());
        });
        assertThat(auditCount()).isEqualTo(2);
    }

    @Test
    void suaTenChiDoiTenVaTruongKhongDoiCode() {
        TenantResponse created = createLib("lib-r");
        OrganizationResponse org = orgs.create(new OrganizationRequest("Trường R"), operator);

        TenantResponse updated = update.execute(created.id(), new UpdateTenantRequest("Tên mới", org.id()), operator);

        assertThat(updated.code()).isEqualTo("lib-r");
        assertThat(updated.name()).isEqualTo("Tên mới");
        assertThat(updated.organizationId()).isEqualTo(org.id());
    }

    @Test
    void timKiemVaChiTietCoSoThanhVienVaQuanTriVien() {
        TenantResponse a = createLib("lib-find-a");
        createLib("lib-find-b");
        status.suspend(a.id(), operator);

        PageResponse<TenantResponse> suspended = search.execute(new TenantSearchRequest("find", TenantStatus.SUSPENDED,
                null, null, null));
        assertThat(suspended.items()).extracting(TenantResponse::code).containsExactly("lib-find-a");
        assertThat(suspended.items().get(0).memberCount()).isEqualTo(1);
        assertThat(suspended.size()).isEqualTo(20);

        TenantDetailResponse detail = get.execute(a.id());
        assertThat(detail.adminUserIds()).containsExactly(accounts.byUsername.get("lib-find-a-admin"));
        assertThat(detail.memberCount()).isEqualTo(1);
    }

    @Test
    void moiThaoTacThayDoiPhatDungMotSuKienAudit() {
        TenantResponse created = createLib("lib-audit");                                   // 1
        // thao tác tổ chức không thuộc tenant: chỉ ghi log INFO, không phát sự kiện (support bỏ qua envelope không tenant)
        OrganizationResponse org = orgs.create(new OrganizationRequest("Trường A"), operator);
        orgs.rename(org.id(), new OrganizationRequest("Trường A2"), operator);
        update.execute(created.id(), new UpdateTenantRequest("Đổi tên", org.id()), operator); // 2
        status.suspend(created.id(), operator);                                            // 3
        status.activate(created.id(), operator);                                           // 4
        UUID second = addAdmin.execute(created.id(), new TenantAdminRequest(newAdmin("second-admin"), null),
                operator);                                                                  // 5
        removeAdmin.execute(created.id(), second, operator);                               // 6
        attach.execute(created.id(), new AttachUserRequest(accounts.existing("ke-toan")), operator); // 7

        assertThat(auditCount()).isEqualTo(7);
        assertThat(events.published).filteredOn(e -> e.event() instanceof PlatformOperationEvent)
                .extracting(e -> ((PlatformOperationEvent) e.event()).operation())
                .containsExactly("TENANT_CREATED", "TENANT_UPDATED",
                        "TENANT_SUSPENDED", "TENANT_ACTIVATED", "TENANT_ADMIN_ADDED", "TENANT_ADMIN_REMOVED",
                        "MEMBER_ATTACHED");
        assertThat(events.published).filteredOn(e -> e.event() instanceof PlatformOperationEvent)
                .allSatisfy(e -> assertThat(((PlatformOperationEvent) e.event()).actorId()).isEqualTo(operator));
        assertThat(events.published).noneMatch(e -> e.event() instanceof PlatformOperationEvent p
                && p.operation().startsWith("ORGANIZATION"));
    }

    @Test
    void themRoiGoQuanTriVien() {
        TenantResponse created = createLib("lib-adm");
        UUID existing = accounts.existing("tro-ly");

        UUID added = addAdmin.execute(created.id(), new TenantAdminRequest(null, existing), operator);
        assertThat(added).isEqualTo(existing);
        assertThat(roles.adminsOf(created.id())).contains(existing);
        assertThat(memberships.find(existing, created.id())).isPresent();

        removeAdmin.execute(created.id(), existing, operator);
        assertThat(roles.adminsOf(created.id())).doesNotContain(existing);
        // gỡ người không phải quản trị viên
        assertThatThrownBy(() -> removeAdmin.execute(created.id(), existing, operator))
                .isInstanceOf(PlatformAccountException.class);
    }

    @Test
    void ganTaiKhoanCoSanBatLaiMembershipDaTat() {
        TenantResponse created = createLib("lib-m");
        UUID user = accounts.existing("doc-gia");
        memberships.save(Membership.restore(user, created.id(), null, MembershipStatus.DISABLED, null));

        attach.execute(created.id(), new AttachUserRequest(user), operator);

        assertThat(memberships.find(user, created.id())).hasValueSatisfying(m -> assertThat(m.isActive()).isTrue());
    }

    // ---------------------------------------------------------------- fakes

    record Recorded(DomainEvent event, UUID envelopeTenant) {
    }

    /** Ghi lại event và tenant của TenantContext lúc phát (envelope lấy tenantId từ đó). */
    static class RecordingEvents implements PublishPlatformEventPort {
        final List<Recorded> published = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            published.add(new Recorded(event, TenantContext.current().orElse(null)));
        }
    }

    record FakeRole(UUID id, UUID tenantId, String name, boolean admin) {
    }

    /** Role theo tenant của TenantContext hiện tại, giống adapter thật (RLS/@TenantId). */
    static class FakeRoles implements TenantRoleProvisioningPort {
        final List<FakeRole> roles = new ArrayList<>();
        final Map<UUID, List<UUID>> admins = new HashMap<>();
        final List<UUID> seededInContext = new ArrayList<>();

        /** Mẫu giả: SUPER_ADMIN là lõi; LIBRARIAN, MEMBER, READER, AUDITOR thuộc LENDING; CASHIER thuộc SALES. */
        static final Map<String, String> TEMPLATE_MODULE = new java.util.LinkedHashMap<>();

        static {
            TEMPLATE_MODULE.put("SUPER_ADMIN", null);
            TEMPLATE_MODULE.put("LIBRARIAN", "LENDING");
            TEMPLATE_MODULE.put("MEMBER", "LENDING");
            TEMPLATE_MODULE.put("READER", "LENDING");
            TEMPLATE_MODULE.put("AUDITOR", "LENDING");
            TEMPLATE_MODULE.put("CASHIER", "SALES");
            TEMPLATE_MODULE.put("STOCKKEEPER", "SALES");
        }

        final List<String> seededModules = new ArrayList<>();

        @Override
        public int seedFromTemplates(java.util.Collection<String> modules) {
            UUID tenant = TenantContext.require();
            seededInContext.add(tenant);
            seededModules.addAll(modules);
            int created = 0;
            for (Map.Entry<String, String> t : TEMPLATE_MODULE.entrySet()) {
                if (t.getValue() == null || modules.contains(t.getValue())) created += addIfAbsent(tenant, t.getKey());
            }
            return created;
        }

        @Override
        public int seedModuleTemplates(String module) {
            UUID tenant = TenantContext.require();
            seededInContext.add(tenant);
            int created = 0;
            for (Map.Entry<String, String> t : TEMPLATE_MODULE.entrySet()) {
                if (module.equals(t.getValue())) created += addIfAbsent(tenant, t.getKey());
            }
            return created;
        }

        private int addIfAbsent(UUID tenant, String name) {
            if (rolesOf(tenant).stream().anyMatch(r -> r.name().equals(name))) return 0;
            roles.add(new FakeRole(UUID.randomUUID(), tenant, name, name.equals("SUPER_ADMIN")));
            return 1;
        }

        @Override
        public void grantTenantAdmin(UUID userId) {
            UUID tenant = TenantContext.require();
            if (rolesOf(tenant).stream().noneMatch(FakeRole::admin)) throw new IllegalStateException("không có role admin");
            List<UUID> list = admins.computeIfAbsent(tenant, t -> new ArrayList<>());
            if (!list.contains(userId)) list.add(userId);
        }

        @Override
        public boolean revokeTenantAdmin(UUID userId) {
            return admins.getOrDefault(TenantContext.require(), new ArrayList<>()).remove(userId);
        }

        @Override
        public List<UUID> listTenantAdmins() {
            return List.copyOf(admins.getOrDefault(TenantContext.require(), List.of()));
        }

        List<FakeRole> rolesOf(UUID tenantId) {
            return roles.stream().filter(r -> r.tenantId().equals(tenantId)).toList();
        }

        List<UUID> adminsOf(UUID tenantId) {
            return admins.getOrDefault(tenantId, List.of());
        }
    }

    /** Tài khoản toàn cục; tạo mới thì thêm membership vào tenant hiện tại (như CreateUserUseCase). */
    static class FakeAccounts implements TenantAccountPort {
        final Map<String, UUID> byUsername = new LinkedHashMap<>();
        private final FakeMemberships memberships;

        FakeAccounts(FakeMemberships memberships) {
            this.memberships = memberships;
        }

        UUID existing(String username) {
            UUID id = UUID.randomUUID();
            byUsername.put(username, id);
            return id;
        }

        @Override
        public UUID createAccount(NewAdmin account) {
            if (byUsername.containsKey(account.username())) {
                throw new PlatformAccountException(PlatformAccountException.Reason.DUPLICATE, "Đã tồn tại");
            }
            UUID id = existing(account.username());
            memberships.save(Membership.create(id, TenantContext.require(), null));
            return id;
        }

        @Override
        public boolean exists(UUID userId) {
            return byUsername.containsValue(userId);
        }
    }

    static class FakeTenants implements TenantRepository, TenantQueryRepository {
        final Map<UUID, Tenant> byId = new LinkedHashMap<>();
        FakeMemberships memberships;

        @Override
        public Tenant save(Tenant tenant) {
            boolean codeTaken = byId.values().stream()
                    .anyMatch(t -> t.code().equals(tenant.code()) && !t.id().equals(tenant.id()));
            if (codeTaken) throw new DuplicateTenantException("trùng");
            byId.put(tenant.id(), tenant);
            return tenant;
        }

        @Override
        public Optional<Tenant> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Tenant> findByCode(String code) {
            return byId.values().stream().filter(t -> t.code().equals(code)).findFirst();
        }

        @Override
        public List<Tenant> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public TenantPage search(TenantCriteria c) {
            List<Tenant> matched = byId.values().stream()
                    .filter(t -> c.keyword() == null || t.code().contains(c.keyword()) || t.name().contains(c.keyword()))
                    .filter(t -> c.status() == null || t.status() == c.status())
                    .filter(t -> c.organizationId() == null || c.organizationId().equals(t.organizationId()))
                    .toList();
            return new TenantPage(matched.stream().skip((long) c.page() * c.size()).limit(c.size()).toList(),
                    matched.size());
        }

        @Override
        public Map<UUID, Long> countActiveMembers(Collection<UUID> tenantIds) {
            Map<UUID, Long> counts = new HashMap<>();
            for (UUID id : tenantIds) {
                counts.put(id, memberships == null ? 0L : memberships.all.values().stream()
                        .filter(m -> m.tenantId().equals(id) && m.isActive()).count());
            }
            return counts;
        }
    }

    {
        tenants.memberships = memberships;
    }

    static class FakeOrganizations implements OrganizationRepository {
        final Map<UUID, Organization> byId = new LinkedHashMap<>();

        @Override
        public Organization save(Organization organization) {
            byId.put(organization.id(), organization);
            return organization;
        }

        @Override
        public Optional<Organization> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Organization> findAll() {
            return List.copyOf(byId.values());
        }
    }

    static class FakeMemberships implements MembershipRepository {
        final Map<String, Membership> all = new LinkedHashMap<>();

        private static String key(UUID userId, UUID tenantId) {
            return userId + "/" + tenantId;
        }

        @Override
        public Membership save(Membership membership) {
            all.put(key(membership.userId(), membership.tenantId()), membership);
            return membership;
        }

        @Override
        public List<Membership> findActiveByUser(UUID userId) {
            return all.values().stream().filter(m -> m.userId().equals(userId) && m.isActive()).toList();
        }

        @Override
        public Optional<Membership> find(UUID userId, UUID tenantId) {
            return Optional.ofNullable(all.get(key(userId, tenantId)));
        }

        @Override
        public boolean existsForUser(UUID userId) {
            return all.values().stream().anyMatch(m -> m.userId().equals(userId));
        }

        @Override
        public void saveLastTenant(UUID userId, UUID tenantId) {
        }

        @Override
        public Optional<UUID> findLastTenant(UUID userId) {
            return Optional.empty();
        }
    }
}
