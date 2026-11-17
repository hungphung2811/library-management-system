package lms.sales.contact;

import static lms.sales.contact.ContactFixture.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lms.sales.contact.application.ShopContactRepository;
import lms.sales.contact.application.SubmitShopContactUseCase;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import org.junit.jupiter.api.Test;

/** T1/T6 (phần application): gửi, trùng R4, giới hạn R5, danh bạ tenant (AC12), đổi trạng thái, ẩn danh, audit không PII. */
class ShopContactUseCaseTest {

    private final ContactFixture f = new ContactFixture();

    @Test
    void guiHopLeTaoMotDongNewGhiDanhBaTenantVaAuditKhongPii() {
        assertEquals(SubmitShopContactUseCase.Outcome.CREATED, f.send("Đơn của tôi chưa giao"));
        assertEquals(1, f.count(f.tenant));
        ShopContactRequest r = f.only(f.tenant);
        assertEquals(ContactStatus.NEW, r.status());
        assertEquals(f.user, r.customerUserId());
        assertThat(f.knownTenants).containsExactly(f.tenant); // AC12
        assertThat(f.audits).hasSize(1);
        SalesAuditPort.AuditEntry a = f.audits.get(0);
        assertEquals(SalesAuditPort.Actions.SHOP_CONTACT_CREATED, a.action());
        assertNull(a.actorId()); // cố ý: audit giữ vĩnh viễn, không nối lại người gửi
        assertEquals(r.id(), a.aggregateId());
        assertThat(a.data()).containsOnlyKeys("topic").containsEntry("topic", "ORDER");
        assertThat(a.toString()).doesNotContain("binh@example.vn").doesNotContain("Bình").doesNotContain("0912");
    }

    @Test
    void khongCoNguoiDungThiTuChoiVaKhongGhiDong() {
        assertEquals(ErrorCode.USER_REQUIRED, codeOf(() -> f.send(f.tenant, null, "xin chao")));
        assertEquals(0, f.rows.size());
        assertThat(f.knownTenants).isEmpty();
    }

    @Test
    void loiNhapKhongGhiGiVaConsentThieuLaConsentRequired() {
        assertEquals(ErrorCode.CONSENT_REQUIRED, codeOf(() -> f.submit.execute(new SubmitShopContactUseCase.Command(
                f.tenant, f.user, ContactTopic.OTHER, "A", "a@b.vn", null, "m", false))));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> f.submit.execute(new SubmitShopContactUseCase.Command(
                f.tenant, f.user, ContactTopic.OTHER, "A", "sai", null, "m", true))));
        assertEquals(0, f.rows.size());
        assertThat(f.audits).isEmpty();
    }

    @Test
    void guiTrungTrong10PhutKhongGhiDongMoiSau10PhutThiGhi() {
        f.send("noi dung");
        f.clock.advance(Duration.ofMinutes(9));
        assertEquals(SubmitShopContactUseCase.Outcome.DUPLICATE, f.send("noi dung"));
        assertEquals(1, f.count(f.tenant));
        assertThat(f.audits).hasSize(1); // trùng không ghi audit
        f.clock.advance(Duration.ofMinutes(2)); // 11 phút kể từ lần đầu
        assertEquals(SubmitShopContactUseCase.Outcome.CREATED, f.send("noi dung"));
        assertEquals(2, f.count(f.tenant));
    }

    @Test
    void trungSoSanhSauChuanHoa() {
        f.send("dong 1\r\ndong 2");
        assertEquals(SubmitShopContactUseCase.Outcome.DUPLICATE, f.send("  dong 1\ndong 2 "));
        assertEquals(1, f.count(f.tenant));
    }

    @Test
    void yeuCauThuSauTrong24GioBi429NguoiKhacCungTenantKhongBiAnhHuong() {
        for (int i = 1; i <= 5; i++) {
            assertEquals(SubmitShopContactUseCase.Outcome.CREATED, f.send("noi dung " + i));
            f.clock.advance(Duration.ofMinutes(20));
        }
        assertEquals(ErrorCode.SHOP_CONTACT_LIMIT_EXCEEDED, codeOf(() -> f.send("noi dung 6")));
        assertEquals(5, f.count(f.tenant));
        // bấm lại yêu cầu thứ 5 (trong 10 phút) vẫn là gửi lại, không phải vượt giới hạn
        f.clock.set(Instant.parse("2026-11-17T03:00:00Z").plus(Duration.ofMinutes(20 * 4 + 5)));
        assertEquals(SubmitShopContactUseCase.Outcome.DUPLICATE, f.send("noi dung 5"));
        // người khác cùng tenant không bị ảnh hưởng
        assertEquals(SubmitShopContactUseCase.Outcome.CREATED, f.send(f.tenant, UUID.randomUUID(), "noi dung 6"));
        // cùng người ở tenant khác không bị ảnh hưởng
        assertEquals(SubmitShopContactUseCase.Outcome.CREATED, f.send(UUID.randomUUID(), f.user, "noi dung 6"));
    }

    @Test
    void sauHai4GioDongCuKhongConTinhVaoGioiHan() {
        for (int i = 1; i <= 5; i++) {
            f.send("noi dung " + i);
            f.clock.advance(Duration.ofMinutes(5));
        }
        f.clock.advance(Duration.ofHours(24));
        assertEquals(SubmitShopContactUseCase.Outcome.CREATED, f.send("noi dung 6"));
    }

    // ---- nhân viên ----

    @Test
    void doiTrangThaiDungVersionGhiAuditFromTo() {
        f.send("m");
        ShopContactRequest r = f.only(f.tenant);
        ShopContactRequest p = f.workflow.changeStatus(f.tenant, f.staff, r.id(), ContactStatus.IN_PROGRESS,
                r.version());
        assertEquals(ContactStatus.IN_PROGRESS, p.status());
        ShopContactRequest c = f.workflow.changeStatus(f.tenant, f.staff, r.id(), ContactStatus.CLOSED, p.version());
        assertEquals(f.clock.instant(), c.closedAt());
        SalesAuditPort.AuditEntry last = f.audits.get(f.audits.size() - 1);
        assertEquals(SalesAuditPort.Actions.SHOP_CONTACT_STATUS_CHANGED, last.action());
        assertEquals(f.staff, last.actorId());
        assertThat(last.data()).containsOnlyKeys("from", "to").containsEntry("from", "IN_PROGRESS")
                .containsEntry("to", "CLOSED");
    }

    @Test
    void versionLechHoacChuyenSaiLa409VaKhongAudit() {
        f.send("m");
        ShopContactRequest r = f.only(f.tenant);
        int audits = f.audits.size();
        assertEquals(ErrorCode.SHOP_CONTACT_STATE_CONFLICT, codeOf(() -> f.workflow.changeStatus(f.tenant, f.staff,
                r.id(), ContactStatus.IN_PROGRESS, r.version() + 1)));
        assertEquals(ErrorCode.SHOP_CONTACT_STATE_CONFLICT, codeOf(() -> f.workflow.changeStatus(f.tenant, f.staff,
                r.id(), ContactStatus.NEW, r.version())));
        ShopContactRequest closed = f.workflow.changeStatus(f.tenant, f.staff, r.id(), ContactStatus.CLOSED,
                r.version());
        assertEquals(ErrorCode.SHOP_CONTACT_STATE_CONFLICT, codeOf(() -> f.workflow.changeStatus(f.tenant, f.staff,
                r.id(), ContactStatus.IN_PROGRESS, closed.version())));
        assertEquals(audits + 1, f.audits.size());
    }

    @Test
    void idCuaTenantKhacHoacKhongCoLa404CungMa() {
        f.send("m");
        ShopContactRequest r = f.only(f.tenant);
        UUID other = UUID.randomUUID();
        assertEquals(ErrorCode.SHOP_CONTACT_NOT_FOUND, codeOf(() -> f.queries.get(other, r.id())));
        assertEquals(ErrorCode.SHOP_CONTACT_NOT_FOUND, codeOf(() -> f.queries.contact(other, r.id())));
        assertEquals(ErrorCode.SHOP_CONTACT_NOT_FOUND, codeOf(() -> f.queries.get(f.tenant, UUID.randomUUID())));
        assertEquals(ErrorCode.SHOP_CONTACT_NOT_FOUND, codeOf(() -> f.workflow.changeStatus(other, f.staff, r.id(),
                ContactStatus.CLOSED, r.version())));
        assertEquals(ErrorCode.SHOP_CONTACT_NOT_FOUND, codeOf(() -> f.workflow.anonymize(other, f.staff, r.id())));
        assertEquals(0, f.queries.search(other, new ShopContactRepository.SearchQuery(null, null, null, null, 0, 20))
                .items().size());
    }

    @Test
    void timKiemLocTheoTrangThaiChuDeVaKhoangThoiGianMoiNhatTruoc() {
        f.send("a");
        f.clock.advance(Duration.ofHours(1));
        f.send("b");
        ShopContactRequest b = f.only(f.tenant);
        f.workflow.changeStatus(f.tenant, f.staff, b.id(), ContactStatus.CLOSED, b.version());
        Instant t0 = Instant.parse("2026-11-17T03:00:00Z");
        var all = f.queries.search(f.tenant, new ShopContactRepository.SearchQuery(null, null, null, null, 0, 20));
        assertEquals(2, all.total());
        assertEquals(b.id(), all.items().get(0).id());
        assertEquals(1, f.queries.search(f.tenant, new ShopContactRepository.SearchQuery(ContactStatus.NEW, null,
                null, null, 0, 20)).total());
        assertEquals(0, f.queries.search(f.tenant, new ShopContactRepository.SearchQuery(null, ContactTopic.PAYMENT,
                null, null, 0, 20)).total());
        // from bao gồm, to loại trừ
        assertEquals(1, f.queries.search(f.tenant, new ShopContactRepository.SearchQuery(null, null, t0,
                t0.plusSeconds(3600), 0, 20)).total());
        assertEquals(2, f.queries.search(f.tenant, new ShopContactRepository.SearchQuery(null, null, t0,
                t0.plusSeconds(3601), 0, 20)).total());
    }

    @Test
    void anonymizeNgayXoaPiiGhiAuditKhongPiiVaGoiLaiLaNoOp() {
        f.send("noi dung bi mat");
        ShopContactRequest r = f.only(f.tenant);
        ShopContactRequest a = f.workflow.anonymize(f.tenant, f.staff, r.id());
        assertEquals(f.clock.instant(), a.piiAnonymizedAt());
        assertNull(a.message());
        var info = f.queries.contact(f.tenant, r.id());
        assertNull(info.fullName());
        assertNull(info.email());
        assertNull(info.phone());
        assertEquals(a.piiAnonymizedAt(), info.piiAnonymizedAt());
        int audits = f.audits.size();
        f.clock.advance(Duration.ofMinutes(1));
        ShopContactRequest again = f.workflow.anonymize(f.tenant, f.staff, r.id());
        assertEquals(a.piiAnonymizedAt(), again.piiAnonymizedAt());
        assertEquals(audits, f.audits.size());
        SalesAuditPort.AuditEntry last = f.audits.get(audits - 1);
        assertEquals(SalesAuditPort.Actions.SHOP_CONTACT_ANONYMIZED, last.action());
        assertEquals(f.staff, last.actorId());
        assertThat(last.data()).isEmpty();
        // trạng thái vẫn đổi được sau ẩn danh
        assertEquals(ContactStatus.IN_PROGRESS, f.workflow.changeStatus(f.tenant, f.staff, r.id(),
                ContactStatus.IN_PROGRESS, again.version()).status());
    }

    // ---- ẩn danh theo hạn ----

    @Test
    void jobAnDanhTungTenantTheoThoiHanRiengMocTinhTuCreatedAtMoiTrangThai() {
        UUID tenantB = UUID.randomUUID();
        f.retentionMonths.put(f.tenant, 12);
        f.retentionMonths.put(tenantB, 3);
        // tạo cách đây ~13 tháng, ~4 tháng, ~1 tháng ở cả hai tenant
        Instant now = f.clock.instant();
        f.clock.set(now.minus(Duration.ofDays(395)));
        f.send("cu A");
        f.send(tenantB, f.user, "cu B");
        f.clock.set(now.minus(Duration.ofDays(120)));
        f.send("giua A");
        f.send(tenantB, f.user, "giua B");
        f.clock.set(now.minus(Duration.ofDays(30)));
        f.send("moi A");
        f.send(tenantB, f.user, "moi B");
        // dòng NEW cũ vẫn bị ẩn danh (mọi trạng thái)
        f.clock.set(now);

        int a = f.expire.execute(f.tenant);
        int b = f.expire.execute(tenantB);
        assertEquals(1, a); // chỉ dòng 395 ngày
        assertEquals(2, b); // 395 và 120 ngày
        assertEquals(1, f.rows.values().stream()
                .filter(r -> r.tenantId().equals(f.tenant) && r.isAnonymized()).count());
        assertEquals(2, f.rows.values().stream()
                .filter(r -> r.tenantId().equals(tenantB) && r.isAnonymized()).count());
        assertEquals(0, f.expire.execute(f.tenant)); // chạy lại không đổi gì
        assertEquals(0, f.expire.execute(tenantB));
        // audit theo lô: không PII, actor null
        var batchAudits = f.audits.stream()
                .filter(x -> SalesAuditPort.Actions.SHOP_CONTACT_PII_ANONYMIZED.equals(x.action())).toList();
        assertThat(batchAudits).hasSize(2);
        assertThat(batchAudits).allSatisfy(x -> {
            assertNull(x.actorId());
            assertThat(x.data()).containsOnlyKeys("requests", "cutoff");
        });
    }

    @Test
    void jobXuLyNhieuLoVoiKichThuocLoNho() {
        Instant now = f.clock.instant();
        f.clock.set(now.minus(Duration.ofDays(400)));
        for (int i = 0; i < 5; i++) {
            f.send("cu " + i);
        }
        f.clock.set(now);
        assertEquals(5, f.expire.execute(f.tenant)); // lô kích thước 2: 2 + 2 + 1
        assertThat(f.audits.stream().filter(x -> SalesAuditPort.Actions.SHOP_CONTACT_PII_ANONYMIZED
                .equals(x.action())).count()).isEqualTo(3);
    }
}
