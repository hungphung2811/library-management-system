package lms.identity.tenant.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.common.kernel.DomainEvent;
import lms.identity.tenant.application.AddStorefrontOriginUseCase;
import lms.identity.tenant.application.ListMyStorefrontOriginsUseCase;
import lms.identity.tenant.application.ListStorefrontOriginsUseCase;
import lms.identity.tenant.application.PublishPlatformEventPort;
import lms.identity.tenant.application.RemoveStorefrontOriginUseCase;
import lms.identity.tenant.application.ResolveStorefrontTenantUseCase;
import lms.identity.tenant.application.SetStorefrontOriginEnabledUseCase;
import lms.identity.tenant.application.StorefrontOriginPolicy;
import lms.identity.tenant.application.StorefrontOriginResponse;
import lms.identity.tenant.application.TransactionalRunner;
import lms.identity.tenant.domain.InvalidStorefrontOriginException;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.StorefrontOriginAction;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginLimitExceededException;
import lms.identity.tenant.domain.StorefrontOriginNotFoundException;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Use case origin storefront (ADR-0013): trùng toàn hệ thống, giới hạn 10, https/prod, lịch sử, event, cô lập tenant. */
class StorefrontOriginUseCasesTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final InMemoryStorefrontOrigins origins = new InMemoryStorefrontOrigins();
    private final List<DomainEvent> published = new ArrayList<>();
    private final PublishPlatformEventPort events = published::add;
    private final TransactionalRunner tx = new TransactionalRunner();
    private final UUID admin = UUID.randomUUID();
    private Tenant a;
    private Tenant b;

    @BeforeEach
    void setUp() {
        a = tenant("shop-a");
        b = tenant("shop-b");
    }

    private Tenant tenant(String code) {
        Tenant t = Tenant.restore(UUID.randomUUID(), code, "Hiệu sách " + code, null, TenantStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(tenants.findById(t.id())).thenReturn(Optional.of(t));
        when(tenants.findByIdForUpdate(t.id())).thenReturn(Optional.of(t));
        return t;
    }

    private AddStorefrontOriginUseCase add(StorefrontOriginPolicy policy) {
        return new AddStorefrontOriginUseCase(tenants, origins, events, tx, policy);
    }

    private AddStorefrontOriginUseCase add() {
        return add(StorefrontOriginPolicy.defaults());
    }

    @Test
    void themChuanHoaGhiLichSuMotDongVaPhatEventSauCommit() {
        StorefrontOriginResponse r = add().execute(a.id(), "HTTPS://Shop-A.vn:443/", admin);

        assertThat(r.origin()).isEqualTo("https://shop-a.vn");
        assertThat(r.enabled()).isTrue();
        assertThat(r.createdBy()).isEqualTo(admin);
        assertThat(origins.rows).hasSize(1);
        assertThat(origins.history).singleElement().satisfies(h -> {
            assertThat(h.action()).isEqualTo(StorefrontOriginAction.ADDED);
            assertThat(h.origin()).isEqualTo("https://shop-a.vn");
            assertThat(h.tenantId()).isEqualTo(a.id());
            assertThat(h.originId()).isEqualTo(r.id());
            assertThat(h.actorId()).isEqualTo(admin);
        });
        assertThat(published).singleElement().isInstanceOfSatisfying(PlatformOperationEvent.class, e -> {
            assertThat(e.operation()).isEqualTo("STOREFRONT_ORIGIN_ADDED");
            assertThat(e.tenantId()).isEqualTo(a.id());
            assertThat(e.targetId()).isEqualTo(r.id());
            assertThat(e.detail()).isEqualTo("origin=https://shop-a.vn");
        });
    }

    @Test
    void originSaiKhongGhiGiVaKhongPhatEvent() {
        assertThatThrownBy(() -> add().execute(a.id(), "https://shop-a.vn/path", admin))
                .isInstanceOf(InvalidStorefrontOriginException.class);
        assertThat(origins.rows).isEmpty();
        assertThat(origins.history).isEmpty();
        assertThat(published).isEmpty();
    }

    @Test
    void tenantKhongTonTaiLa404() {
        assertThatThrownBy(() -> add().execute(UUID.randomUUID(), "https://x.vn", admin))
                .isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void trungTrongCungTenantBi409() {
        add().execute(a.id(), "https://shop-a.vn", admin);

        assertThatThrownBy(() -> add().execute(a.id(), "https://SHOP-A.vn:443", admin))
                .isInstanceOf(StorefrontOriginDuplicatedException.class);
        assertThat(origins.rows).hasSize(1);
        assertThat(origins.history).hasSize(1);
    }

    @Test
    void trungVoiTenantKhacBi409KemChuSoHuu() {
        add().execute(a.id(), "https://shop.vn", admin);

        assertThatThrownBy(() -> add().execute(b.id(), "https://shop.vn", admin))
                .isInstanceOfSatisfying(StorefrontOriginDuplicatedException.class,
                        e -> assertThat(e.ownerTenantId()).isEqualTo(a.id()));
        assertThat(origins.findByTenant(b.id())).isEmpty();
    }

    @Test
    void trungVoiDongDangTatVanBi409() {
        StorefrontOriginResponse r = add().execute(a.id(), "https://shop.vn", admin);
        new SetStorefrontOriginEnabledUseCase(tenants, origins, events, tx, StorefrontOriginPolicy.defaults())
                .execute(a.id(), r.id(), false, admin);

        assertThatThrownBy(() -> add().execute(b.id(), "https://shop.vn", admin))
                .isInstanceOf(StorefrontOriginDuplicatedException.class);
    }

    @Test
    void thuMuoiMotBiTuChoiVaMuoiLaDuoc() {
        for (int i = 0; i < 10; i++) add().execute(a.id(), "https://s" + i + ".vn", admin);
        assertThat(origins.findByTenant(a.id())).hasSize(10);

        assertThatThrownBy(() -> add().execute(a.id(), "https://s10.vn", admin))
                .isInstanceOf(StorefrontOriginLimitExceededException.class);
        assertThat(origins.findByTenant(a.id())).hasSize(10);
        // giới hạn theo từng tenant: tenant khác vẫn thêm được
        add().execute(b.id(), "https://other.vn", admin);
    }

    @Test
    void gioiHanDemCaDongDangTat() {
        StorefrontOriginPolicy two = new StorefrontOriginPolicy(2, false);
        StorefrontOriginResponse first = add(two).execute(a.id(), "https://one.vn", admin);
        add(two).execute(a.id(), "https://two.vn", admin);
        new SetStorefrontOriginEnabledUseCase(tenants, origins, events, tx, two).execute(a.id(), first.id(), false, admin);

        assertThatThrownBy(() -> add(two).execute(a.id(), "https://three.vn", admin))
                .isInstanceOf(StorefrontOriginLimitExceededException.class);
    }

    @Test
    void productionChiHttpsTruLocalhostVaCo() {
        assertThatThrownBy(() -> add().execute(a.id(), "http://shop.vn", admin))
                .isInstanceOfSatisfying(InvalidStorefrontOriginException.class, e -> assertThat(e.reason())
                        .isEqualTo(InvalidStorefrontOriginException.Reason.HTTP_NOT_ALLOWED));
        assertThat(add().execute(a.id(), "http://localhost:5173", admin).origin()).isEqualTo("http://localhost:5173");
        assertThat(add().execute(a.id(), "http://127.0.0.1:3000", admin).origin()).isEqualTo("http://127.0.0.1:3000");
        // cờ STOREFRONT_ALLOW_HTTP_ORIGINS bật: staging http
        assertThat(add(new StorefrontOriginPolicy(10, true)).execute(a.id(), "http://staging.shop.vn", admin).origin())
                .isEqualTo("http://staging.shop.vn");
    }

    @Test
    void tatBatGhiLichSuVaGiaTriKhongDoiLaNoOp() {
        StorefrontOriginResponse r = add().execute(a.id(), "https://shop.vn", admin);
        published.clear();
        var set = new SetStorefrontOriginEnabledUseCase(tenants, origins, events, tx, StorefrontOriginPolicy.defaults());

        StorefrontOriginResponse off = set.execute(a.id(), r.id(), false, admin);
        assertThat(off.enabled()).isFalse();
        assertThat(origins.history).extracting(h -> h.action()).containsExactly(StorefrontOriginAction.ADDED,
                StorefrontOriginAction.DISABLED);
        assertThat(published).singleElement().isInstanceOfSatisfying(PlatformOperationEvent.class,
                e -> assertThat(e.operation()).isEqualTo("STOREFRONT_ORIGIN_DISABLED"));

        // gọi lại cùng giá trị: no-op
        set.execute(a.id(), r.id(), false, admin);
        assertThat(origins.history).hasSize(2);
        assertThat(published).hasSize(1);

        set.execute(a.id(), r.id(), true, admin);
        assertThat(origins.history.get(2).action()).isEqualTo(StorefrontOriginAction.ENABLED);
        assertThat(published).hasSize(2);
    }

    @Test
    void originIdCuaTenantKhacLa404VaKhongDoiGi() {
        StorefrontOriginResponse ofB = add().execute(b.id(), "https://b.vn", admin);
        int historyBefore = origins.history.size();

        assertThatThrownBy(() -> new SetStorefrontOriginEnabledUseCase(tenants, origins, events, tx,
                StorefrontOriginPolicy.defaults()).execute(a.id(), ofB.id(), false, admin))
                .isInstanceOf(StorefrontOriginNotFoundException.class);
        assertThatThrownBy(() -> new RemoveStorefrontOriginUseCase(tenants, origins, events, tx,
                StorefrontOriginPolicy.defaults()).execute(a.id(), ofB.id(), admin))
                .isInstanceOf(StorefrontOriginNotFoundException.class);

        assertThat(origins.rows.get(ofB.id()).enabled()).isTrue();
        assertThat(origins.history).hasSize(historyBefore);
    }

    @Test
    void xoaGiuChuoiOriginTrongLichSuVaChoPhepThemLaiChoTenantKhac() {
        StorefrontOriginResponse r = add().execute(a.id(), "https://shop.vn", admin);
        published.clear();

        new RemoveStorefrontOriginUseCase(tenants, origins, events, tx, StorefrontOriginPolicy.defaults())
                .execute(a.id(), r.id(), admin);

        assertThat(origins.rows).isEmpty();
        assertThat(origins.history).hasSize(2);
        assertThat(origins.history.get(1).action()).isEqualTo(StorefrontOriginAction.REMOVED);
        assertThat(origins.history.get(1).origin()).isEqualTo("https://shop.vn");
        assertThat(published).singleElement().isInstanceOfSatisfying(PlatformOperationEvent.class,
                e -> assertThat(e.operation()).isEqualTo("STOREFRONT_ORIGIN_REMOVED"));
        // chuyển domain sang tenant khác = xóa rồi thêm
        assertThat(add().execute(b.id(), "https://shop.vn", admin).origin()).isEqualTo("https://shop.vn");
    }

    @Test
    void danhSachChiGomOriginCuaTenantKemGioiHan() {
        add().execute(a.id(), "https://a1.vn", admin);
        add().execute(a.id(), "https://a2.vn", admin);
        add().execute(b.id(), "https://b1.vn", admin);

        var list = new ListStorefrontOriginsUseCase(tenants, origins, StorefrontOriginPolicy.defaults()).execute(a.id());
        assertThat(list.maxOrigins()).isEqualTo(10);
        assertThat(list.origins()).extracting(StorefrontOriginResponse::origin).containsExactly("https://a1.vn", "https://a2.vn");
        assertThatThrownBy(() -> new ListStorefrontOriginsUseCase(tenants, origins, StorefrontOriginPolicy.defaults())
                .execute(UUID.randomUUID())).isInstanceOf(TenantNotFoundException.class);
    }

    @Test
    void tenantAdminChiThayOriginCuaTenantMinhVaKhongCoNguoiTao() {
        add().execute(a.id(), "https://a1.vn", admin);
        add().execute(b.id(), "https://b1.vn", admin);

        var mine = lms.tenant.TenantContext.callAs(a.id(), () -> new ListMyStorefrontOriginsUseCase(origins).execute());

        assertThat(mine.origins()).singleElement().satisfies(i -> {
            assertThat(i.origin()).isEqualTo("https://a1.vn");
            assertThat(i.enabled()).isTrue();
        });
        // DTO chỉ có origin + enabled
        assertThat(mine.origins().get(0).getClass().getRecordComponents()).extracting(c -> c.getName())
                .containsExactly("origin", "enabled");
    }

    @Test
    void tenantAdminKhongCoTenantTrongNguCanhThiLoi() {
        lms.tenant.TenantContext.clear();
        assertThatThrownBy(() -> new ListMyStorefrontOriginsUseCase(origins).execute())
                .isInstanceOf(lms.tenant.TenantRequiredException.class);
    }

    @Test
    void capTokenStorefrontChiTraOriginDangBat() {
        InMemoryTenantModules modules = new InMemoryTenantModules();
        modules.give(a.id(), ModuleCode.LENDING, ModuleCode.SALES);
        when(tenants.findByCode("shop-a")).thenReturn(Optional.of(a));
        StorefrontOriginResponse on = add().execute(a.id(), "https://on.vn", admin);
        StorefrontOriginResponse off = add().execute(a.id(), "https://off.vn", admin);
        new SetStorefrontOriginEnabledUseCase(tenants, origins, events, tx, StorefrontOriginPolicy.defaults())
                .execute(a.id(), off.id(), false, admin);
        add().execute(b.id(), "https://of-b.vn", admin);

        var found = new ResolveStorefrontTenantUseCase(tenants, modules, origins).execute("shop-a");

        assertThat(found).isPresent();
        assertThat(found.get().allowedOrigins()).containsExactly("https://on.vn");
        assertThat(on.origin()).isEqualTo("https://on.vn");
    }

    @Test
    void tenantKhongKhaDungVanRongMotKetQua() {
        InMemoryTenantModules modules = new InMemoryTenantModules();
        modules.give(a.id(), ModuleCode.LENDING); // không SALES
        when(tenants.findByCode("shop-a")).thenReturn(Optional.of(a));
        add().execute(a.id(), "https://on.vn", admin);

        assertThat(new ResolveStorefrontTenantUseCase(tenants, modules, origins).execute("shop-a")).isEmpty();
    }
}
