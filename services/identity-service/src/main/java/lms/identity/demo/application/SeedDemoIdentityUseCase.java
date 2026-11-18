package lms.identity.demo.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.demo.plan.DemoPlan.ReaderPlan;
import lms.demo.plan.DemoPlan.StaffPlan;
import lms.demo.plan.DemoPlan.StaffRole;
import lms.identity.accesscontrol.application.AssignRoleByNameUseCase;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractRequests.BillingCycleRequest;
import lms.identity.contract.application.ContractRequests.ContractRequest;
import lms.identity.contract.application.ManageContractUseCase;
import lms.identity.tenant.application.CreateTenantRequest;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.FindTenantByCodeQuery;
import lms.identity.tenant.application.NewAdmin;
import lms.identity.tenant.application.TenantCreationOptions;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.EnsureReaderCardUseCase;
import lms.identity.user.application.EnsureUserUseCase;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.UserPersonalInfo;
import lms.tenant.TenantContext;

/**
 * Seed phần identity của dữ liệu demo (ADR-0018): tenant {@code demo} (LENDING + SALES, id tất định
 * {@link DemoIds#tenant()}), một hợp đồng hiệu lực sắp hết hạn, quản trị viên tenant, thủ thư, quản lý cửa hàng và độc
 * giả, tất cả với id từ {@link DemoPlan}. Idempotent theo khóa tự nhiên (mã tenant, id người dùng, tên hợp đồng, số
 * thẻ): chạy lại chỉ bổ sung phần thiếu; bản ghi đã có chỉ được điền các trường cá nhân còn null, không đè giá trị đã có. Chỉ dùng use case sẵn có của các context (kèm vài
 * lớp bọc "đảm bảo tồn tại"); event Kafka do các use case phát ra là event thật và được giữ.
 */
public class SeedDemoIdentityUseCase {

    public static final String CONTRACT_NAME = "Hợp đồng thuê dịch vụ Demo";
    /** Hợp đồng bắt đầu từ ngần này ngày trước và kết thúc sau {@link #CONTRACT_DAYS_LEFT} ngày (dashboard platform). */
    static final int CONTRACT_DAYS_ELAPSED = 345;
    public static final int CONTRACT_DAYS_LEFT = 20;
    static final long CONTRACT_AMOUNT = 24_000_000L;
    static final String LIBRARIAN_ROLE = "LIBRARIAN";
    static final String STORE_MANAGER_ROLE = "STORE_MANAGER";
    /** Người thao tác ghi trong log/audit của hợp đồng và event tenant: không phải tài khoản thật. */
    static final UUID SEED_ACTOR = DemoIds.of("seed-actor", 0);

    /** Số bản ghi tạo mới / đã có sẵn nên bỏ qua. */
    public record Result(int created, int skipped) {
    }

    private final FindTenantByCodeQuery tenants;
    private final CreateTenantUseCase createTenant;
    private final ContractQueryUseCase contractQuery;
    private final ManageContractUseCase manageContract;
    private final EnsureUserUseCase ensureUser;
    private final EnsureReaderCardUseCase ensureReaderCard;
    private final AssignRoleByNameUseCase assignRole;

    public SeedDemoIdentityUseCase(FindTenantByCodeQuery tenants, CreateTenantUseCase createTenant,
                                   ContractQueryUseCase contractQuery, ManageContractUseCase manageContract,
                                   EnsureUserUseCase ensureUser, EnsureReaderCardUseCase ensureReaderCard,
                                   AssignRoleByNameUseCase assignRole) {
        this.tenants = tenants;
        this.createTenant = createTenant;
        this.contractQuery = contractQuery;
        this.manageContract = manageContract;
        this.ensureUser = ensureUser;
        this.ensureReaderCard = ensureReaderCard;
        this.assignRole = assignRole;
    }

    /**
     * @throws IllegalStateException nếu tenant mã {@code demo} đã tồn tại nhưng không phải tenant demo (id khác id tất
     *                               định): từ chối để không trộn dữ liệu demo vào tenant thật
     */
    public Result execute(DemoPlan plan, String password) {
        Counter counter = new Counter();
        UUID tenantId = DemoIds.tenant();
        StaffPlan admin = staffOf(plan, StaffRole.TENANT_ADMIN);

        ensureTenant(tenantId, admin, password, counter);
        ensureContract(tenantId, plan.today(), counter);
        TenantContext.runAs(tenantId, () -> {
            ensureUser.fillMissing(admin.id(), DemoPersonProfiles.staff(admin));
            ensureStaff(staffOf(plan, StaffRole.LIBRARIAN), LIBRARIAN_ROLE, password, counter);
            ensureStaff(staffOf(plan, StaffRole.STORE_MANAGER), STORE_MANAGER_ROLE, password, counter);
            for (ReaderPlan reader : plan.readers()) ensureReader(reader, plan.today(), password, counter);
        });
        return new Result(counter.created, counter.skipped);
    }

    private void ensureTenant(UUID tenantId, StaffPlan admin, String password, Counter counter) {
        Optional<FindTenantByCodeQuery.Found> existing = tenants.execute(DemoPlan.TENANT_CODE);
        if (existing.isPresent()) {
            if (!existing.get().id().equals(tenantId)) {
                throw new IllegalStateException("Đã có tenant mã '" + DemoPlan.TENANT_CODE
                        + "' không phải tenant demo (id " + existing.get().id() + "), từ chối seed");
            }
            counter.skipped += 2; // tenant + quản trị viên (cùng một transaction nên cùng tồn tại)
            return;
        }
        createTenant.execute(new CreateTenantRequest(DemoPlan.TENANT_CODE, DemoPlan.TENANT_NAME, null,
                new NewAdmin(admin.username(), admin.fullName(), admin.email(), password), null,
                List.of("LENDING", "SALES")), SEED_ACTOR, new TenantCreationOptions(tenantId, admin.id(), false));
        counter.created += 2; // tenant + quản trị viên (tạo cùng một transaction)
    }

    private void ensureContract(UUID tenantId, LocalDate today, Counter counter) {
        boolean exists = contractQuery.listByTenant(tenantId).contracts().stream()
                .anyMatch(c -> CONTRACT_NAME.equals(c.name()));
        if (exists) {
            counter.skipped++;
            return;
        }
        var draft = manageContract.create(tenantId, new ContractRequest(CONTRACT_NAME, List.of("LENDING", "SALES"),
                today.minusDays(CONTRACT_DAYS_ELAPSED), today.plusDays(CONTRACT_DAYS_LEFT), CONTRACT_AMOUNT,
                new BillingCycleRequest("YEAR", 1), "Dữ liệu demo (ADR-0018)", null), SEED_ACTOR);
        manageContract.activate(draft.id(), SEED_ACTOR);
        counter.created++;
    }

    private void ensureStaff(StaffPlan staff, String roleName, String password, Counter counter) {
        boolean created = ensureUser.execute(new CreateUserRequest(staff.id(), staff.username(), staff.fullName(),
                staff.email(), null, password, false));
        counter.count(created);
        ensureUser.fillMissing(staff.id(), DemoPersonProfiles.staff(staff));
        counter.count(assignRole.execute(staff.id(), roleName));
    }

    private void ensureReader(ReaderPlan reader, LocalDate today, String password, Counter counter) {
        counter.count(ensureUser.execute(new CreateUserRequest(reader.id(), reader.username(), reader.fullName(),
                reader.email(), null, password, false)));
        boolean lecturer = reader.index() % 6 == 5;
        ReaderPersonalInfo info = DemoPersonProfiles.reader(reader, lecturer, today);
        // thông tin cá nhân dùng chung cũng ghi vào users (chỉ điền trường còn null)
        ensureUser.fillMissing(reader.id(), new UserPersonalInfo(info.phone(), info.dateOfBirth(), info.gender(),
                info.address(), null, null, null));
        counter.count(ensureReaderCard.execute(reader.id(), reader.cardNo(), reader.fullName(), reader.email(),
                lecturer, today.plusYears(1), info));
    }

    private static StaffPlan staffOf(DemoPlan plan, StaffRole role) {
        return plan.staff().stream().filter(s -> s.role() == role).findFirst()
                .orElseThrow(() -> new IllegalStateException("Kế hoạch demo thiếu nhân sự " + role));
    }

    private static final class Counter {
        int created;
        int skipped;

        void count(boolean wasCreated) {
            if (wasCreated) created++;
            else skipped++;
        }
    }
}
