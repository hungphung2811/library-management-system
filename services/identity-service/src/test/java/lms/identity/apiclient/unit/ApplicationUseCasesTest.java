package lms.identity.apiclient.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.common.kernel.DomainEvent;
import lms.identity.apiclient.application.ApplicationPolicy;
import lms.identity.apiclient.application.ApplicationViews.ApplicationDetail;
import lms.identity.apiclient.application.ApplicationViews.CreatedCredential;
import lms.identity.apiclient.application.CreateApplicationUseCase.Command;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationHistoryAction;
import lms.identity.apiclient.domain.ApplicationHistoryEntry;
import lms.identity.apiclient.domain.ApplicationStatus;
import lms.identity.apiclient.domain.ClientSecret;
import lms.identity.apiclient.domain.CredentialStatus;
import lms.identity.apiclient.domain.RevokeReason;
import lms.identity.apiclient.domain.SiteKey;
import lms.identity.tenant.application.ApplicationOriginException;
import org.junit.jupiter.api.Test;

/**
 * Use case quản lý Application (SaaS API Authentication, pha P1): tạo/sửa/tạm ngưng/thu hồi, scope (Q5/Q6), credential
 * (D7, Q13, tối đa 2 ACTIVE), domain allowlist (tái dùng luật ADR-0013), lịch sử và cô lập tenant (T-P2).
 */
class ApplicationUseCasesTest {

    private final ApiClientFixture f = new ApiClientFixture();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    private ApplicationDetail site(UUID tenant, String name) {
        return f.create.execute(tenant, new Command(name, "PUBLIC_SITE", null), admin);
    }

    private ApplicationDetail integration(UUID tenant, String name) {
        return f.create.execute(tenant, new Command(name, "INTEGRATION", 600), admin);
    }

    // ------------------------------------------------------------------ tạo

    @Test
    void taoWebsiteSinhSiteKeyHopLeKhongCoCredentialVaGhiLichSuMotDong() {
        ApplicationDetail created = site(tenantA, "  Website chính ");

        assertThat(created.name()).isEqualTo("Website chính");
        assertThat(created.kind()).isEqualTo("PUBLIC_SITE");
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(SiteKey.isValid(created.siteKey())).isTrue();
        assertThat(created.clientId()).isEqualTo(created.id());
        assertThat(created.scopes()).isEmpty();
        assertThat(created.credentials()).isEmpty();
        assertThat(f.history.rows).singleElement().satisfies(h -> {
            assertThat(h.action()).isEqualTo(ApplicationHistoryAction.CREATED);
            assertThat(h.actorId()).isEqualTo(admin);
            assertThat(h.tenantId()).isEqualTo(tenantA);
        });
        assertThat(f.events).hasSize(1);
    }

    @Test
    void taoTichHopCanHopDongActiveVaKhongCoSiteKey() {
        f.contract = false;
        assertThatThrownBy(() -> integration(tenantA, "ERP"))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.CONTRACT_REQUIRED));
        assertThat(f.applications.rows).isEmpty();
        // website không cần hợp đồng
        site(tenantA, "Web");

        f.contract = true;
        ApplicationDetail erp = integration(tenantA, "ERP");
        assertThat(erp.siteKey()).isNull();
        assertThat(erp.rateLimitPerMinute()).isEqualTo(600);
    }

    @Test
    void loaiKhongHopLeTenRongVaHanMucSaiBiTuChoi() {
        for (Command bad : List.of(new Command("A", "BACKEND", null), new Command("A", null, null),
                new Command(" ", "PUBLIC_SITE", null), new Command("x".repeat(101), "PUBLIC_SITE", null),
                new Command("A", "PUBLIC_SITE", 0))) {
            assertThatThrownBy(() -> f.create.execute(tenantA, bad, admin))
                    .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                            .isEqualTo(Reason.INVALID_APPLICATION));
        }
        assertThat(f.applications.rows).isEmpty();
    }

    @Test
    void tenKhongTrungTrongTenantKhongPhanBietHoaThuongNhungTenantKhacDungDuoc() {
        site(tenantA, "Web");
        assertThatThrownBy(() -> site(tenantA, "WEB"))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NAME_DUPLICATED));
        site(tenantB, "Web");
    }

    @Test
    void gioiHanSoApplicationMoiTenant() {
        ApiClientFixture small = new ApiClientFixture(new ApplicationPolicy(2, Duration.ofDays(365),
                Duration.ofDays(730)));
        small.create.execute(tenantA, new Command("A", "PUBLIC_SITE", null), admin);
        small.create.execute(tenantA, new Command("B", "PUBLIC_SITE", null), admin);
        assertThatThrownBy(() -> small.create.execute(tenantA, new Command("C", "PUBLIC_SITE", null), admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_LIMIT_EXCEEDED));
        // tenant khác không bị ảnh hưởng; xóa mềm giải phóng chỗ
        small.create.execute(tenantB, new Command("C", "PUBLIC_SITE", null), admin);
        small.manage.delete(tenantA, small.applications.findByTenant(tenantA).get(0).id(), admin);
        small.create.execute(tenantA, new Command("C", "PUBLIC_SITE", null), admin);
    }

    // ------------------------------------------------------------------ scope (Q5, Q6)

    @Test
    void capScopeDocLaThanhCongVaGhiLichSuKhongDoiKhiBiTuChoi() {
        ApplicationDetail erp = integration(tenantA, "ERP");

        ApplicationDetail granted = f.manage.replaceScopes(tenantA, erp.id(),
                List.of("orders:read", "books:read", "books:read"), admin);
        assertThat(granted.scopes()).containsExactly("books:read", "orders:read");
        assertThat(f.history.rows.get(f.history.rows.size() - 1)).satisfies(h -> {
            assertThat(h.action()).isEqualTo(ApplicationHistoryAction.SCOPES_CHANGED);
            assertThat(h.detail()).isEqualTo("scopes=books:read,orders:read");
        });

        // scope ghi (Q6) và customers:* (Q5) có trong danh mục nhưng bị từ chối, không đổi gì
        for (String code : List.of("books:write", "inventory:write", "orders:write", "customers:read",
                "customers:write")) {
            assertThatThrownBy(() -> f.manage.replaceScopes(tenantA, erp.id(), List.of("books:read", code), admin))
                    .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                            .isEqualTo(Reason.SCOPE_NOT_GRANTABLE));
        }
        assertThat(f.query.get(tenantA, erp.id()).scopes()).containsExactly("books:read", "orders:read");
    }

    @Test
    void scopeNgoaiDanhMucBiTuChoiVaDanhSachRongThuHoiHet() {
        ApplicationDetail erp = integration(tenantA, "ERP");
        for (String bad : List.of("admin", "books", "BOOKS:READ", "*", "")) {
            assertThatThrownBy(() -> f.manage.replaceScopes(tenantA, erp.id(), List.of(bad), admin))
                    .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                            .isEqualTo(Reason.INVALID_SCOPE));
        }
        f.manage.replaceScopes(tenantA, erp.id(), List.of("inventory:read"), admin);
        assertThat(f.manage.replaceScopes(tenantA, erp.id(), List.of(), admin).scopes()).isEmpty();
    }

    @Test
    void websiteKhongCoScopeVaUngDungTamNgungKhongDoiDuocScope() {
        ApplicationDetail web = site(tenantA, "Web");
        assertThatThrownBy(() -> f.manage.replaceScopes(tenantA, web.id(), List.of("books:read"), admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.KIND_MISMATCH));

        ApplicationDetail erp = integration(tenantA, "ERP");
        f.manage.suspend(tenantA, erp.id(), admin);
        assertThatThrownBy(() -> f.manage.replaceScopes(tenantA, erp.id(), List.of("books:read"), admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NOT_ACTIVE));
    }

    // ------------------------------------------------------------------ credential (D7, Q13)

    @Test
    void secretChiTraMotLanVaChiLuuHashHintKhongRoRiVaoLichSuEventHayToString() {
        ApplicationDetail erp = integration(tenantA, "ERP");

        CreatedCredential created = f.creds.create(tenantA, erp.id(), null, admin);

        String secret = created.clientSecret();
        assertThat(secret).startsWith("lms_sk_").hasSize("lms_sk_".length() + 43);
        assertThat(created.clientId()).isEqualTo(erp.id());
        assertThat(created.secretHint()).isEqualTo(secret.substring(secret.length() - 4));
        // chỉ có SHA-256 hex được lưu
        ApplicationCredential stored = f.credentials.rows.get(created.credentialId());
        assertThat(stored.secretHash()).isEqualTo(ClientSecret.hash(secret)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(stored.secretHash()).doesNotContain(secret);
        assertThat(ClientSecret.matches(secret, stored.secretHash())).isTrue();
        assertThat(ClientSecret.matches(secret + "x", stored.secretHash())).isFalse();
        // không lộ ở nơi nào khác: lịch sử, event, toString, các lần đọc sau
        assertThat(f.history.rows).allSatisfy(h -> assertThat(String.valueOf(h.detail())).doesNotContain(secret));
        assertThat(f.events.toString()).doesNotContain(secret);
        assertThat(created.toString()).doesNotContain(secret);
        assertThat(f.query.get(tenantA, erp.id()).toString()).doesNotContain(secret).doesNotContain(stored.secretHash());
        assertThat(f.query.get(tenantA, erp.id()).credentials()).singleElement().satisfies(c -> {
            assertThat(c.secretHint()).isEqualTo(created.secretHint());
            assertThat(c.status()).isEqualTo("ACTIVE");
            assertThat(c.usable()).isTrue();
        });
        assertThat(f.query.list(tenantA).get(0).usableCredentials()).isEqualTo(1);
        // hai lần tạo cho hai secret khác nhau
        assertThat(f.creds.create(tenantA, erp.id(), null, admin).clientSecret()).isNotEqualTo(secret);
    }

    @Test
    void hanDungMacDinh365NgayTuyChinhVaGioiHanToiDa() {
        ApplicationDetail erp = integration(tenantA, "ERP");

        CreatedCredential def = f.creds.create(tenantA, erp.id(), null, admin);
        assertThat(def.expiresAt()).isEqualTo(def.createdAt().plus(Duration.ofDays(365)));
        f.creds.revoke(tenantA, erp.id(), def.credentialId(), null, admin);
        CreatedCredential custom = f.creds.create(tenantA, erp.id(), 30, admin);
        assertThat(custom.expiresAt()).isEqualTo(custom.createdAt().plus(Duration.ofDays(30)));
        for (Integer bad : List.of(0, -1, 731)) {
            assertThatThrownBy(() -> f.creds.create(tenantA, erp.id(), bad, admin))
                    .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                            .isEqualTo(Reason.INVALID_APPLICATION));
        }
    }

    @Test
    void toiDaHaiCredentialDungDuocCungLucThuHoiHoacHetHanMoLaiCho() {
        ApplicationDetail erp = integration(tenantA, "ERP");
        CreatedCredential first = f.creds.create(tenantA, erp.id(), 10, admin);
        CreatedCredential second = f.creds.create(tenantA, erp.id(), 100, admin);

        assertThatThrownBy(() -> f.creds.create(tenantA, erp.id(), null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.CREDENTIAL_LIMIT_EXCEEDED));
        assertThat(f.credentials.rows).hasSize(2);

        // thu hồi cũ (kết thúc xoay vòng) mở chỗ
        f.creds.revoke(tenantA, erp.id(), first.credentialId(), "ROTATED", admin);
        CreatedCredential third = f.creds.create(tenantA, erp.id(), null, admin);
        assertThat(third.credentialId()).isNotEqualTo(second.credentialId());

        // credential hết hạn không chiếm chỗ: second (100 ngày) hết hạn -> chỉ còn third dùng được
        assertThatThrownBy(() -> f.creds.create(tenantA, erp.id(), null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.CREDENTIAL_LIMIT_EXCEEDED));
        f.clock.advance(Duration.ofDays(101));
        assertThat(f.query.get(tenantA, erp.id()).credentials()).filteredOn(c -> c.usable()).hasSize(1);
        f.creds.create(tenantA, erp.id(), null, admin);
    }

    @Test
    void thuHoiCoLyDoGhiLichSuVaKhongThuHoiLanHai() {
        ApplicationDetail erp = integration(tenantA, "ERP");
        CreatedCredential c = f.creds.create(tenantA, erp.id(), null, admin);

        var view = f.creds.revoke(tenantA, erp.id(), c.credentialId(), "COMPROMISED", admin);

        assertThat(view.status()).isEqualTo("REVOKED");
        assertThat(view.usable()).isFalse();
        assertThat(view.revokeReason()).isEqualTo("COMPROMISED");
        ApplicationCredential stored = f.credentials.rows.get(c.credentialId());
        assertThat(stored.status()).isEqualTo(CredentialStatus.REVOKED);
        assertThat(stored.revokedBy()).isEqualTo(admin);
        assertThat(f.history.rows.get(f.history.rows.size() - 1).action())
                .isEqualTo(ApplicationHistoryAction.CREDENTIAL_REVOKED);
        assertThatThrownBy(() -> f.creds.revoke(tenantA, erp.id(), c.credentialId(), null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.CREDENTIAL_NOT_ACTIVE));
        assertThatThrownBy(() -> f.creds.revoke(tenantA, erp.id(), UUID.randomUUID(), null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.CREDENTIAL_NOT_FOUND));
        // lý do hệ thống không được đặt từ ngoài
        CreatedCredential d = f.creds.create(tenantA, erp.id(), null, admin);
        assertThatThrownBy(() -> f.creds.revoke(tenantA, erp.id(), d.credentialId(), "APPLICATION_DELETED", admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.INVALID_APPLICATION));
    }

    @Test
    void websiteKhongCoCredentialVaUngDungTamNgungKhongTaoDuocCredential() {
        ApplicationDetail web = site(tenantA, "Web");
        assertThatThrownBy(() -> f.creds.create(tenantA, web.id(), null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.KIND_MISMATCH));

        ApplicationDetail erp = integration(tenantA, "ERP");
        f.manage.suspend(tenantA, erp.id(), admin);
        assertThatThrownBy(() -> f.creds.create(tenantA, erp.id(), null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NOT_ACTIVE));
        assertThat(f.credentials.rows).isEmpty();
    }

    // ------------------------------------------------------------------ vòng đời

    @Test
    void suaTamNgungKichHoatVaLichSu() {
        ApplicationDetail web = site(tenantA, "Web");

        ApplicationDetail updated = f.manage.update(tenantA, web.id(), "Web mới", 300, admin);
        assertThat(updated.name()).isEqualTo("Web mới");
        assertThat(updated.rateLimitPerMinute()).isEqualTo(300);
        assertThat(f.manage.suspend(tenantA, web.id(), admin).status()).isEqualTo("SUSPENDED");
        assertThatThrownBy(() -> f.manage.suspend(tenantA, web.id(), admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NOT_ACTIVE));
        assertThatThrownBy(() -> f.manage.update(tenantA, web.id(), "Khác", null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NOT_ACTIVE));
        assertThat(f.manage.reactivate(tenantA, web.id(), admin).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> f.manage.reactivate(tenantA, web.id(), admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NOT_SUSPENDED));

        assertThat(f.query.history(tenantA, web.id())).extracting(h -> h.action()).containsExactly(
                "REACTIVATED", "SUSPENDED", "UPDATED", "CREATED");
        assertThat(f.history.rows).allSatisfy(h -> {
            assertThat(h.actorId()).isEqualTo(admin);
            assertThat(h.occurredAt()).isNotNull();
        });
    }

    @Test
    void suaTrungTenCuaUngDungKhacBiTuChoiNhungGiuTenCuaChinMinhDuoc() {
        ApplicationDetail a = site(tenantA, "A");
        site(tenantA, "B");
        assertThatThrownBy(() -> f.manage.update(tenantA, a.id(), "b", null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NAME_DUPLICATED));
        assertThat(f.manage.update(tenantA, a.id(), "a", null, admin).name()).isEqualTo("a");
    }

    @Test
    void thuHoiXoaMemThuHoiCredentialVaXoaDomainRoiBiAnKhoiApi() {
        ApplicationDetail web = site(tenantA, "Web");
        f.domains.add(tenantA, web.id(), "https://shop.a.vn", admin);
        ApplicationDetail erp = integration(tenantA, "ERP");
        CreatedCredential c1 = f.creds.create(tenantA, erp.id(), null, admin);
        f.creds.create(tenantA, erp.id(), null, admin);

        f.manage.delete(tenantA, erp.id(), admin);
        f.manage.delete(tenantA, web.id(), admin);

        assertThat(f.credentials.rows.values()).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo(CredentialStatus.REVOKED);
            assertThat(c.revokeReason()).isEqualTo(RevokeReason.APPLICATION_DELETED);
        });
        assertThat(f.originRows.rows).isEmpty();
        assertThat(f.applications.rows.get(erp.id()).status()).isEqualTo(ApplicationStatus.DELETED);
        assertThat(f.query.list(tenantA)).isEmpty();
        assertThatThrownBy(() -> f.query.get(tenantA, erp.id())).isInstanceOfSatisfying(ApplicationException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.APPLICATION_NOT_FOUND));
        assertThatThrownBy(() -> f.creds.revoke(tenantA, erp.id(), c1.credentialId(), null, admin))
                .isInstanceOf(ApplicationException.class);
        assertThat(f.history.rows).filteredOn(h -> h.action() == ApplicationHistoryAction.DELETED).hasSize(2);
        // site key đã phát không được tái sử dụng: tên giải phóng nhưng key cũ vẫn nằm trong bảng
        assertThat(f.applications.existsSiteKey(web.siteKey())).isTrue();
        assertThatThrownBy(() -> f.manage.delete(tenantA, web.id(), admin)).isInstanceOf(ApplicationException.class);
    }

    @Test
    void xoaySiteKeyDoiKeyChiChoWebsite() {
        ApplicationDetail web = site(tenantA, "Web");
        ApplicationDetail rotated = f.manage.rotateSiteKey(tenantA, web.id(), admin);
        assertThat(rotated.siteKey()).isNotEqualTo(web.siteKey());
        assertThat(SiteKey.isValid(rotated.siteKey())).isTrue();
        assertThat(f.history.rows.get(f.history.rows.size() - 1)).satisfies(h -> {
            assertThat(h.action()).isEqualTo(ApplicationHistoryAction.SITE_KEY_ROTATED);
            assertThat(String.valueOf(h.detail())).doesNotContain(rotated.siteKey());
        });
        ApplicationDetail erp = integration(tenantA, "ERP");
        assertThatThrownBy(() -> f.manage.rotateSiteKey(tenantA, erp.id(), admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.KIND_MISMATCH));
    }

    // ------------------------------------------------------------------ domain allowlist (ADR-0013)

    @Test
    void domainTaiDungLuatChuanHoaGhiLichSuVaGanDungApplication() {
        ApplicationDetail web = site(tenantA, "Web");
        ApplicationDetail other = site(tenantA, "Web 2");

        var added = f.domains.add(tenantA, web.id(), "HTTPS://Shop-A.vn:443/", admin);

        assertThat(added.origin()).isEqualTo("https://shop-a.vn");
        assertThat(f.originRows.rows.get(added.id()).applicationId()).isEqualTo(web.id());
        assertThat(f.domains.list(tenantA, web.id()).domains()).hasSize(1);
        assertThat(f.domains.list(tenantA, other.id()).domains()).isEmpty();
        assertThat(f.originRows.history).hasSize(1);
        assertThat(f.history.rows.get(f.history.rows.size() - 1)).satisfies(h -> {
            assertThat(h.action()).isEqualTo(ApplicationHistoryAction.DOMAIN_ADDED);
            assertThat(h.detail()).isEqualTo("origin=https://shop-a.vn");
        });
        // origin của ứng dụng khác không xóa được qua ứng dụng này
        assertThatThrownBy(() -> f.domains.remove(tenantA, other.id(), added.id(), admin))
                .isInstanceOfSatisfying(ApplicationOriginException.class, e -> assertThat(e.reason())
                        .isEqualTo(ApplicationOriginException.Reason.NOT_FOUND));
        f.domains.remove(tenantA, web.id(), added.id(), admin);
        assertThat(f.originRows.rows).isEmpty();
    }

    @Test
    void domainSaiDinhDangTrungToanHeThongVaGioiHan() {
        ApplicationDetail web = site(tenantA, "Web");
        ApplicationDetail webB = site(tenantB, "Web");
        for (String bad : List.of("*", "https://a.vn/path", "ftp://a.vn", "http://shop.vn", "null", "")) {
            assertThatThrownBy(() -> f.domains.add(tenantA, web.id(), bad, admin))
                    .isInstanceOfSatisfying(ApplicationOriginException.class, e -> assertThat(e.reason())
                            .isEqualTo(ApplicationOriginException.Reason.INVALID));
        }
        f.domains.add(tenantA, web.id(), "https://shop.a.vn", admin);
        // trùng với chính mình hoặc tenant khác: cùng một lỗi, KHÔNG lộ tenant chủ
        assertThatThrownBy(() -> f.domains.add(tenantB, webB.id(), "https://shop.a.vn", admin))
                .isInstanceOfSatisfying(ApplicationOriginException.class, e -> {
                    assertThat(e.reason()).isEqualTo(ApplicationOriginException.Reason.DUPLICATED);
                    assertThat(e.getMessage()).doesNotContain(tenantA.toString());
                    assertThat(e.detail()).isNull();
                });
        for (int i = 1; i < 10; i++) f.domains.add(tenantA, web.id(), "https://s" + i + ".a.vn", admin);
        assertThatThrownBy(() -> f.domains.add(tenantA, web.id(), "https://s10.a.vn", admin))
                .isInstanceOfSatisfying(ApplicationOriginException.class, e -> assertThat(e.reason())
                        .isEqualTo(ApplicationOriginException.Reason.LIMIT_EXCEEDED));
    }

    @Test
    void domainChiChoWebsiteConActive() {
        ApplicationDetail erp = integration(tenantA, "ERP");
        assertThatThrownBy(() -> f.domains.add(tenantA, erp.id(), "https://shop.a.vn", admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.KIND_MISMATCH));
        assertThatThrownBy(() -> f.domains.list(tenantA, erp.id())).isInstanceOf(ApplicationException.class);

        ApplicationDetail web = site(tenantA, "Web");
        f.manage.suspend(tenantA, web.id(), admin);
        assertThatThrownBy(() -> f.domains.add(tenantA, web.id(), "https://shop.a.vn", admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.APPLICATION_NOT_ACTIVE));
    }

    // ------------------------------------------------------------------ T-P2: IDOR hai tenant

    @Test
    void tenantKhacLuonNhan404CungThanVoiKhongTonTaiVaListKhongThayDuLieuTenantKhac() {
        ApplicationDetail web = site(tenantA, "Web A");
        ApplicationDetail erp = integration(tenantA, "ERP A");
        CreatedCredential cred = f.creds.create(tenantA, erp.id(), null, admin);
        var domain = f.domains.add(tenantA, web.id(), "https://shop.a.vn", admin);
        site(tenantB, "Web B");

        UUID ghost = UUID.randomUUID();
        List<org.junit.jupiter.api.function.Executable> attacks = List.of(
                () -> f.query.get(tenantB, erp.id()),
                () -> f.query.history(tenantB, erp.id()),
                () -> f.manage.update(tenantB, erp.id(), "Hack", null, admin),
                () -> f.manage.suspend(tenantB, erp.id(), admin),
                () -> f.manage.reactivate(tenantB, erp.id(), admin),
                () -> f.manage.delete(tenantB, erp.id(), admin),
                () -> f.manage.replaceScopes(tenantB, erp.id(), List.of("books:read"), admin),
                () -> f.manage.rotateSiteKey(tenantB, web.id(), admin),
                () -> f.creds.create(tenantB, erp.id(), null, admin),
                () -> f.creds.revoke(tenantB, erp.id(), cred.credentialId(), null, admin),
                () -> f.creds.list(tenantB, erp.id()),
                () -> f.domains.list(tenantB, web.id()),
                () -> f.domains.add(tenantB, web.id(), "https://evil.vn", admin),
                () -> f.domains.remove(tenantB, web.id(), domain.id(), admin));
        for (org.junit.jupiter.api.function.Executable attack : attacks) {
            ApplicationException e = org.junit.jupiter.api.Assertions.assertThrows(ApplicationException.class, attack);
            assertThat(e.reason()).isEqualTo(Reason.APPLICATION_NOT_FOUND);
            // cùng thân với id không tồn tại
            ApplicationException same = org.junit.jupiter.api.Assertions.assertThrows(ApplicationException.class,
                    () -> f.query.get(tenantB, ghost));
            assertThat(e.getMessage()).isEqualTo(same.getMessage());
        }

        // credential của application A không thu hồi được bằng cách đứng trong application của B
        UUID bApp = f.applications.findByTenant(tenantB).get(0).id();
        assertThatThrownBy(() -> f.creds.revoke(tenantB, bApp, cred.credentialId(), null, admin))
                .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.reason())
                        .isEqualTo(Reason.CREDENTIAL_NOT_FOUND));

        // không có tác dụng phụ trên dữ liệu của A
        assertThat(f.query.get(tenantA, erp.id()).status()).isEqualTo("ACTIVE");
        assertThat(f.query.get(tenantA, erp.id()).name()).isEqualTo("ERP A");
        assertThat(f.credentials.rows.get(cred.credentialId()).status()).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(f.originRows.rows).hasSize(1);
        assertThat(f.query.list(tenantB)).extracting(s -> s.name()).containsExactly("Web B");
        assertThat(f.query.list(tenantA)).extracting(s -> s.name()).containsExactly("Web A", "ERP A");
        assertThat(f.query.history(tenantA, erp.id())).isNotEmpty();
        assertThat(f.history.rows).filteredOn(h -> h.tenantId().equals(tenantB))
                .extracting(ApplicationHistoryEntry::action).containsExactly(ApplicationHistoryAction.CREATED);
    }

    @Test
    void sukienAuditKhongChuaSecretVaMangTenantVaUngDung() {
        ApplicationDetail erp = integration(tenantA, "ERP");
        CreatedCredential c = f.creds.create(tenantA, erp.id(), null, admin);
        f.creds.revoke(tenantA, erp.id(), c.credentialId(), null, admin);

        List<DomainEvent> events = f.events;
        assertThat(events).hasSize(3);
        assertThat(events.toString()).doesNotContain(c.clientSecret()).contains(erp.id().toString());
    }
}
