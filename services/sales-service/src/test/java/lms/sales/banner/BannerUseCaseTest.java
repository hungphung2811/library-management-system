package lms.sales.banner;

import static lms.sales.banner.BannerFixture.cmd;
import static lms.sales.banner.BannerFixture.codeOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.sales.banner.application.BannerAdminUseCases.SaveCommand;
import lms.sales.banner.application.BannerAdminView;
import lms.sales.banner.application.PublicBannerView;
import lms.sales.banner.domain.BannerStatus;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;

class BannerUseCaseTest {

    private final BannerFixture f = new BannerFixture();

    private BannerAdminView create(String title, Integer sort) {
        return f.admin.create(f.tenant, f.staff, new SaveCommand(title, null, null, sort, null, null, null));
    }

    @Test
    void taoGhiAuditChiCoBannerId() {
        BannerAdminView v = f.admin.create(f.tenant, f.staff, new SaveCommand("Giảm 20%", "Đến 30/11",
                "/books?sort=newest", 2, true, null, null));
        assertThat(v.status()).isEqualTo(BannerStatus.LIVE);
        assertThat(v.version()).isZero();
        assertThat(f.audits).hasSize(1);
        SalesAuditPort.AuditEntry a = f.audits.get(0);
        assertThat(a.action()).isEqualTo(SalesAuditPort.Actions.BANNER_CREATED);
        assertThat(a.actorId()).isEqualTo(f.staff);
        assertThat(a.data()).containsOnlyKeys("bannerId");
    }

    @Test
    void banner21BiTuChoiVaKhongGhi() {
        for (int i = 0; i < 20; i++) {
            create("B" + i, i);
        }
        assertThat(codeOf(() -> create("B21", 0))).isEqualTo(ErrorCode.BANNER_LIMIT_EXCEEDED);
        assertThat(f.rows).hasSize(20);
        assertThat(f.audits).hasSize(20);
    }

    @Test
    void gioiHan20TinhRiengTungTenant() {
        for (int i = 0; i < 20; i++) {
            create("B" + i, 0);
        }
        f.admin.create(UUID.randomUUID(), f.staff, cmd("tenant khac"));
    }

    @Test
    void duLieuSaiKhongGhiGiCaVaKhongAudit() {
        assertThat(codeOf(() -> f.admin.create(f.tenant, f.staff,
                new SaveCommand("A", null, "//evil.com", null, null, null, null)))).isEqualTo(ErrorCode.INVALID_BANNER);
        assertThat(f.rows).isEmpty();
        assertThat(f.audits).isEmpty();
    }

    @Test
    void suaDungVersionTangVersionVaGhiAudit() {
        BannerAdminView v = create("A", 0);
        f.now = f.now.plusSeconds(30);
        BannerAdminView u = f.admin.update(f.tenant, f.staff, v.id(), v.version(),
                new SaveCommand("B", null, "/books", 5, false, null, null));
        assertThat(u.version()).isEqualTo(1);
        assertThat(u.title()).isEqualTo("B");
        assertThat(u.status()).isEqualTo(BannerStatus.INACTIVE);
        assertThat(u.createdAt()).isEqualTo(v.createdAt());
        assertThat(u.updatedAt()).isAfter(v.updatedAt());
        assertThat(f.audits.get(1).action()).isEqualTo(SalesAuditPort.Actions.BANNER_UPDATED);
    }

    @Test
    void suaVersionCuBi409() {
        BannerAdminView v = create("A", 0);
        f.admin.update(f.tenant, f.staff, v.id(), 0, cmd("B"));
        assertThat(codeOf(() -> f.admin.update(f.tenant, f.staff, v.id(), 0, cmd("C"))))
                .isEqualTo(ErrorCode.BANNER_VERSION_CONFLICT);
        assertThat(f.admin.get(f.tenant, v.id()).title()).isEqualTo("B");
    }

    @Test
    void suaIdTenantKhacLa404() {
        BannerAdminView v = create("A", 0);
        UUID other = UUID.randomUUID();
        assertThat(codeOf(() -> f.admin.get(other, v.id()))).isEqualTo(ErrorCode.BANNER_NOT_FOUND);
        assertThat(codeOf(() -> f.admin.update(other, f.staff, v.id(), 0, cmd("X"))))
                .isEqualTo(ErrorCode.BANNER_NOT_FOUND);
        assertThat(codeOf(() -> f.admin.delete(other, f.staff, v.id()))).isEqualTo(ErrorCode.BANNER_NOT_FOUND);
        assertThat(f.admin.list(f.tenant)).hasSize(1);
    }

    @Test
    void xoaCungGhiAuditVaXoaLan2La404() {
        BannerAdminView v = create("A", 0);
        f.admin.delete(f.tenant, f.staff, v.id());
        assertThat(f.rows).isEmpty();
        assertThat(f.audits.get(1).action()).isEqualTo(SalesAuditPort.Actions.BANNER_DELETED);
        assertThat(codeOf(() -> f.admin.delete(f.tenant, f.staff, v.id()))).isEqualTo(ErrorCode.BANNER_NOT_FOUND);
        assertThat(f.audits).hasSize(2);
    }

    @Test
    void listQuanTriGomMoiTrangThaiTheoSortOrder() {
        create("c", 3);
        create("a", 1);
        f.admin.create(f.tenant, f.staff, new SaveCommand("off", null, null, 2, false, null, null));
        assertThat(f.admin.list(f.tenant)).extracting(BannerAdminView::title).containsExactly("a", "off", "c");
    }

    @Test
    void congKhaiChiLayDangHieuLucToiDa10TheoSortOrder() {
        for (int i = 0; i < 14; i++) {
            create("B" + i, 14 - i); // B13 có sortOrder 1 ... B0 có sortOrder 14
        }
        f.admin.create(f.tenant, f.staff, new SaveCommand("tat", null, null, 0, false, null, null));
        f.admin.create(f.tenant, f.staff,
                new SaveCommand("tuong lai", null, null, 0, true, f.now.plusSeconds(1), null));
        f.admin.create(f.tenant, f.staff, new SaveCommand("het han", null, null, 0, true, null, f.now));
        List<PublicBannerView> live = f.publicQuery.live(f.tenant);
        assertThat(live).hasSize(10);
        assertThat(live).extracting(PublicBannerView::title).containsExactly("B13", "B12", "B11", "B10", "B9",
                "B8", "B7", "B6", "B5", "B4");
    }

    @Test
    void congKhaiTheoThoiGianVaChiTenantCuaMinh() {
        f.admin.create(f.tenant, f.staff,
                new SaveCommand("sap toi", null, null, 0, true, f.now.plus(Duration.ofHours(1)), null));
        f.admin.create(UUID.randomUUID(), f.staff, cmd("tenant khac"));
        assertThat(f.publicQuery.live(f.tenant)).isEmpty();
        f.now = f.now.plus(Duration.ofHours(1));
        assertThat(f.publicQuery.live(f.tenant)).extracting(PublicBannerView::title).containsExactly("sap toi");
    }
}
