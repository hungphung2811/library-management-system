package lms.identity.apiclient.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.CreateApplicationUseCase.Command;
import lms.identity.apiclient.application.ResolveSiteKeyUseCase;
import lms.identity.tenant.application.ResolveStorefrontTenantUseCase;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phân giải site key (pha P2, T-P3 phía identity): site key hợp lệ cho tenant + application + origin của CHÍNH application;
 * mọi thất bại (sai định dạng, không tồn tại, xoay vòng, application tạm ngưng/xóa/không phải website, tenant ngưng hoặc
 * không bật module) cùng một kết quả rỗng và cùng số truy vấn.
 */
class ResolveSiteKeyUseCaseTest {

    private final ApiClientFixture f = new ApiClientFixture();
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantModuleRepository modules = mock(TenantModuleRepository.class);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private ResolveSiteKeyUseCase resolve;
    private ApplicationDetail site;
    private TenantStatus status = TenantStatus.ACTIVE;
    private List<ModuleCode> enabled = List.of(ModuleCode.SALES);

    @BeforeEach
    void setUp() {
        when(tenants.findById(any())).thenAnswer(i -> {
            UUID id = i.getArgument(0);
            return id.equals(tenantId)
                    ? Optional.of(Tenant.restore(tenantId, "shop-a", "Hiệu sách A", null, status, Instant.parse("2026-01-01T00:00:00Z")))
                    : Optional.<Tenant>empty();
        });
        when(modules.enabledOf(any())).thenAnswer(i -> {
            java.util.Collection<UUID> ids = i.getArgument(0);
            return ids.contains(tenantId) ? Map.of(tenantId, enabled) : Map.<UUID, List<ModuleCode>>of();
        });
        resolve = new ResolveSiteKeyUseCase(f.applications,
                new ResolveStorefrontTenantUseCase(tenants, modules, f.originRows));
        site = f.create.execute(tenantId, new Command("Website A", "PUBLIC_SITE", null), admin);
    }

    @Test
    void siteKeyHopLeTraTenantApplicationVaChiOriginCuaApplication() {
        f.domains.add(tenantId, site.id(), "https://shop.a.vn", admin);
        // origin cấp tenant (application_id IS NULL) thuộc đường {code} cũ: KHÔNG hợp lệ cho site key
        f.originRows.rows.put(UUID.randomUUID(), new StorefrontOriginEntry(UUID.randomUUID(), tenantId,
                "https://legacy.a.vn", true, Instant.parse("2026-01-01T00:00:00Z"), null, Instant.parse("2026-01-01T00:00:00Z"), null));

        var r = resolve.resolve(site.siteKey()).orElseThrow();

        assertThat(r.applicationId()).isEqualTo(site.id());
        assertThat(r.tenant().id()).isEqualTo(tenantId);
        assertThat(r.tenant().code()).isEqualTo("shop-a");
        assertThat(r.tenant().modules()).containsExactly("SALES");
        assertThat(r.tenant().allowedOrigins()).containsExactly("https://shop.a.vn");
    }

    @Test
    void originCuaApplicationKhacCuaCungTenantKhongLotVao() {
        ApplicationDetail other = f.create.execute(tenantId, new Command("Website B", "PUBLIC_SITE", null), admin);
        f.domains.add(tenantId, other.id(), "https://b.vn", admin);
        f.domains.add(tenantId, site.id(), "https://a.vn", admin);

        assertThat(resolve.resolve(site.siteKey()).orElseThrow().tenant().allowedOrigins()).containsExactly("https://a.vn");
        assertThat(resolve.resolve(other.siteKey()).orElseThrow().tenant().allowedOrigins()).containsExactly("https://b.vn");
    }

    @Test
    void originDangTatKhongDuocTra() {
        var origin = f.domains.add(tenantId, site.id(), "https://off.a.vn", admin);
        f.originRows.rows.computeIfPresent(origin.id(), (k, e) -> e.withEnabled(false, Instant.now(), admin));

        assertThat(resolve.resolve(site.siteKey()).orElseThrow().tenant().allowedOrigins()).isEmpty();
    }

    @Test
    void moiLyDoThatBaiCungMotKetQuaRong() {
        String oldKey = site.siteKey();
        // sai định dạng / thiếu / không tồn tại
        assertThat(resolve.resolve(null)).isEmpty();
        assertThat(resolve.resolve("")).isEmpty();
        assertThat(resolve.resolve("lms_pk_short")).isEmpty();
        assertThat(resolve.resolve("lms_pk_" + "A".repeat(32))).isEmpty();
        assertThat(resolve.resolve("lms_sk_" + "A".repeat(32))).isEmpty();

        // xoay vòng: key cũ hết hiệu lực, key mới dùng được
        ApplicationDetail rotated = f.manage.rotateSiteKey(tenantId, site.id(), admin);
        assertThat(resolve.resolve(oldKey)).isEmpty();
        assertThat(resolve.resolve(rotated.siteKey())).isPresent();

        // application tạm ngưng
        f.manage.suspend(tenantId, site.id(), admin);
        assertThat(resolve.resolve(rotated.siteKey())).isEmpty();
        f.manage.reactivate(tenantId, site.id(), admin);
        assertThat(resolve.resolve(rotated.siteKey())).isPresent();

        // tenant ngưng / tắt module
        status = TenantStatus.SUSPENDED;
        assertThat(resolve.resolve(rotated.siteKey())).isEmpty();
        status = TenantStatus.ACTIVE;
        enabled = List.of();
        assertThat(resolve.resolve(rotated.siteKey())).isEmpty();
        enabled = List.of(ModuleCode.SALES);

        // application đã xóa
        f.manage.delete(tenantId, site.id(), admin);
        assertThat(resolve.resolve(rotated.siteKey())).isEmpty();
    }

    @Test
    void ungDungTichHopKhongCoSiteKeyKhongBaoGioKhop() {
        f.create.execute(tenantId, new Command("ERP", "INTEGRATION", null), admin);

        assertThat(resolve.resolve("lms_pk_" + "B".repeat(32))).isEmpty();
    }

    @Test
    void moiNhanhChayDungMotTruyVanTenantVaMotTruyVanOrigin() {
        resolve.resolve(site.siteKey());
        resolve.resolve("lms_pk_" + "C".repeat(32));
        resolve.resolve("sai-dinh-dang");

        // ba lần gọi (thành công, không tồn tại, sai định dạng): mỗi lần đúng một lần tra tenant và module
        verify(tenants, times(3)).findById(any());
        verify(modules, times(3)).enabledOf(any());
    }
}
