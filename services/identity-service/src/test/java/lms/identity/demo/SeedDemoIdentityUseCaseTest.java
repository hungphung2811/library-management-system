package lms.identity.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.demo.plan.DemoIds;
import lms.demo.plan.DemoPlan;
import lms.identity.accesscontrol.application.AssignRoleByNameUseCase;
import lms.identity.contract.application.ContractQueryUseCase;
import lms.identity.contract.application.ContractRequests.ContractRequest;
import lms.identity.contract.application.ContractResponses.ContractResponse;
import lms.identity.contract.application.ContractResponses.TenantContractsResponse;
import lms.identity.contract.application.ManageContractUseCase;
import lms.identity.demo.application.SeedDemoIdentityUseCase;
import lms.identity.tenant.application.CreateTenantRequest;
import lms.identity.tenant.application.CreateTenantUseCase;
import lms.identity.tenant.application.FindTenantByCodeQuery;
import lms.identity.tenant.application.TenantCreationOptions;
import lms.identity.tenant.domain.TenantStatus;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.EnsureReaderCardUseCase;
import lms.identity.user.application.EnsureUserUseCase;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Seed identity demo: dùng bản giả có trạng thái (tập id đã tạo) thay cho DB, nên chạy hai lần kiểm được idempotent.
 * Đường thật trên PostgreSQL ở {@code DemoSeedIdentityIT}.
 */
class SeedDemoIdentityUseCaseTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    static final String PASSWORD = "Demo@Pass-123";
    final DemoPlan plan = DemoPlan.generate(TODAY, DemoPlan.DEFAULT_SEED);

    FindTenantByCodeQuery tenants = mock(FindTenantByCodeQuery.class);
    CreateTenantUseCase createTenant = mock(CreateTenantUseCase.class);
    ContractQueryUseCase contractQuery = mock(ContractQueryUseCase.class);
    ManageContractUseCase manageContract = mock(ManageContractUseCase.class);
    EnsureUserUseCase ensureUser = mock(EnsureUserUseCase.class);
    EnsureReaderCardUseCase ensureCard = mock(EnsureReaderCardUseCase.class);
    AssignRoleByNameUseCase assignRole = mock(AssignRoleByNameUseCase.class);

    // trạng thái "DB" giả
    boolean tenantExists;
    final List<String> contractNames = new ArrayList<>();
    final Set<UUID> users = new HashSet<>();
    final Set<UUID> cards = new HashSet<>();
    final Set<String> roleGrants = new HashSet<>();
    final List<UUID> tenantContextOfUserCalls = new ArrayList<>();

    SeedDemoIdentityUseCase useCase() {
        return new SeedDemoIdentityUseCase(tenants, createTenant, contractQuery, manageContract, ensureUser,
                ensureCard, assignRole);
    }

    @BeforeEach
    void wire() {
        when(tenants.execute(DemoPlan.TENANT_CODE)).thenAnswer(i -> tenantExists
                ? Optional.of(new FindTenantByCodeQuery.Found(DemoIds.tenant(), DemoPlan.TENANT_CODE,
                        DemoPlan.TENANT_NAME, TenantStatus.ACTIVE))
                : Optional.empty());
        when(createTenant.execute(any(CreateTenantRequest.class), any(UUID.class), any(TenantCreationOptions.class)))
                .thenAnswer(i -> {
                    tenantExists = true;
                    users.add(i.<TenantCreationOptions>getArgument(2).adminUserId());
                    return null;
                });
        when(contractQuery.listByTenant(DemoIds.tenant())).thenAnswer(i -> new TenantContractsResponse(
                contractNames.stream().map(SeedDemoIdentityUseCaseTest::contract).toList(), List.of(), List.of()));
        when(manageContract.create(eq(DemoIds.tenant()), any(ContractRequest.class), any(UUID.class)))
                .thenAnswer(i -> {
                    contractNames.add(i.<ContractRequest>getArgument(1).name());
                    return contract(i.<ContractRequest>getArgument(1).name());
                });
        when(ensureUser.execute(any(CreateUserRequest.class))).thenAnswer(i -> {
            tenantContextOfUserCalls.add(TenantContext.current().orElse(null));
            return users.add(i.<CreateUserRequest>getArgument(0).id());
        });
        when(ensureCard.execute(any(UUID.class), anyString(), anyString(), anyString(), anyBoolean(),
                any(LocalDate.class))).thenAnswer(i -> cards.add(i.getArgument(0)));
        when(assignRole.execute(any(UUID.class), anyString()))
                .thenAnswer(i -> roleGrants.add(i.getArgument(0) + "/" + i.getArgument(1)));
    }

    static ContractResponse contract(String name) {
        return new ContractResponse(UUID.randomUUID(), DemoIds.tenant(), name, "ACTIVE", "IN_EFFECT", false, false,
                List.of("LENDING", "SALES"), null, null, null, 0, 0, 0, null, null, null, null, null, null, null,
                null, null, 0);
    }

    @Test
    void lanDau_taoTenantDemoVoiIdTatDinhVaMatKhauKhongBatDoi() {
        SeedDemoIdentityUseCase.Result result = useCase().execute(plan, PASSWORD);

        ArgumentCaptor<CreateTenantRequest> request = ArgumentCaptor.forClass(CreateTenantRequest.class);
        ArgumentCaptor<TenantCreationOptions> options = ArgumentCaptor.forClass(TenantCreationOptions.class);
        verify(createTenant).execute(request.capture(), any(UUID.class), options.capture());
        assertThat(request.getValue().code()).isEqualTo("demo");
        assertThat(request.getValue().modules()).containsExactly("LENDING", "SALES");
        assertThat(request.getValue().newAdmin().temporaryPassword()).isEqualTo(PASSWORD);
        assertThat(request.getValue().newAdmin().email()).endsWith("@demo.local");
        assertThat(options.getValue().tenantId()).isEqualTo(DemoIds.tenant());
        assertThat(options.getValue().adminUserId()).isEqualTo(DemoIds.staff(0));
        assertThat(options.getValue().adminMustChangePassword()).isFalse();
        assertThat(result.created()).isPositive();
    }

    @Test
    void lanDau_taoHopDongHieuLucSapHetHanTrongHaiMuoiNgay() {
        useCase().execute(plan, PASSWORD);

        ArgumentCaptor<ContractRequest> request = ArgumentCaptor.forClass(ContractRequest.class);
        verify(manageContract).create(eq(DemoIds.tenant()), request.capture(), any(UUID.class));
        assertThat(request.getValue().endDate()).isEqualTo(TODAY.plusDays(20));
        assertThat(request.getValue().startDate()).isBefore(TODAY);
        assertThat(request.getValue().modules()).containsExactlyInAnyOrder("LENDING", "SALES");
        verify(manageContract).activate(any(UUID.class), any(UUID.class));
    }

    @Test
    void lanDau_taoNhanSuVaDocGiaTheoPlanTrongNguCanhTenantDemo() {
        useCase().execute(plan, PASSWORD);

        assertThat(users).contains(DemoIds.staff(0), DemoIds.staff(1), DemoIds.staff(2));
        plan.readers().forEach(r -> assertThat(users).contains(r.id()));
        assertThat(cards).hasSameSizeAs(plan.readers());
        assertThat(roleGrants).containsExactlyInAnyOrder(DemoIds.staff(1) + "/LIBRARIAN",
                DemoIds.staff(2) + "/STORE_MANAGER");
        assertThat(tenantContextOfUserCalls).isNotEmpty().containsOnly(DemoIds.tenant());
        ArgumentCaptor<CreateUserRequest> users = ArgumentCaptor.forClass(CreateUserRequest.class);
        verify(ensureUser, times(2 + plan.readers().size())).execute(users.capture());
        assertThat(users.getAllValues()).allSatisfy(u -> {
            assertThat(u.password()).isEqualTo(PASSWORD);
            assertThat(u.mustChangePassword()).isFalse();
            assertThat(u.email()).endsWith("@demo.local");
        });
        // không có tenant context rò ra ngoài bước seed
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void chayHaiLan_lanHaiKhongTaoGiVaKhongLoi() {
        SeedDemoIdentityUseCase.Result first = useCase().execute(plan, PASSWORD);
        int usersAfterFirst = users.size();
        int cardsAfterFirst = cards.size();

        SeedDemoIdentityUseCase.Result second = useCase().execute(plan, PASSWORD);

        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(first.created() + first.skipped());
        assertThat(users).hasSize(usersAfterFirst);
        assertThat(cards).hasSize(cardsAfterFirst);
        assertThat(contractNames).containsExactly(SeedDemoIdentityUseCase.CONTRACT_NAME);
        verify(createTenant, times(1)).execute(any(CreateTenantRequest.class), any(UUID.class),
                any(TenantCreationOptions.class));
        verify(manageContract, times(1)).create(any(UUID.class), any(ContractRequest.class), any(UUID.class));
        verify(manageContract, times(1)).activate(any(UUID.class), any(UUID.class));
    }

    @Test
    void tenantMaDemoKhongPhaiCuaSeed_tuChoiVaKhongGhiGi() {
        when(tenants.execute(DemoPlan.TENANT_CODE)).thenReturn(Optional.of(new FindTenantByCodeQuery.Found(
                UUID.randomUUID(), DemoPlan.TENANT_CODE, "Thư viện thật", TenantStatus.ACTIVE)));

        assertThatThrownBy(() -> useCase().execute(plan, PASSWORD)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("không phải tenant demo");

        verify(createTenant, never()).execute(any(CreateTenantRequest.class), any(UUID.class),
                any(TenantCreationOptions.class));
        verify(ensureUser, never()).execute(any(CreateUserRequest.class));
        verify(manageContract, never()).create(any(UUID.class), any(ContractRequest.class), any(UUID.class));
    }

    @Test
    void lanChayBoSung_tenantCoSanNhungThieuDocGia_chiTaoPhanThieu() {
        tenantExists = true;
        contractNames.add(SeedDemoIdentityUseCase.CONTRACT_NAME);
        users.add(DemoIds.staff(0));
        users.add(DemoIds.staff(1));

        SeedDemoIdentityUseCase.Result result = useCase().execute(plan, PASSWORD);

        verify(createTenant, never()).execute(any(CreateTenantRequest.class), any(UUID.class),
                any(TenantCreationOptions.class));
        verify(manageContract, never()).create(any(UUID.class), any(ContractRequest.class), any(UUID.class));
        // tạo mới: nhân sự 2 + role 2 + (độc giả + thẻ)
        assertThat(result.created()).isEqualTo(1 + 2 + 2 * plan.readers().size());
    }
}
