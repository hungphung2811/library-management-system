package lms.sales.contact.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.junit.jupiter.api.Test;

/** T1 (ADR-0015): chuẩn hóa, độ dài code point, email/điện thoại, consent, máy trạng thái, policy chống spam, ẩn danh. */
class ShopContactDomainTest {

    private static final Instant T0 = Instant.parse("2026-11-17T03:00:00Z");
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private static ShopContactRequest submit(String name, String email, String phone, String message, Boolean consent) {
        return ShopContactRequest.submit(UUID.randomUUID(), TENANT, USER, ContactTopic.ORDER, name, email, phone,
                message, consent, T0);
    }

    private static ShopContactRequest ok() {
        return submit("Nguyễn Văn Bình", "binh@example.vn", "0912 345 678", "Đơn của tôi chưa giao", true);
    }

    private static ErrorCode codeOf(Runnable r) {
        return assertThrows(SalesException.class, r::run).code();
    }

    // ---- chuẩn hóa và kiểm đầu vào ----

    @Test
    void hopLeTaoDongNewVaChuanHoa() {
        // chuỗi NFD (e + dấu) được đưa về NFC, \r\n thành \n, cắt khoảng trắng
        ShopContactRequest r = submit("  Nguyễn  ", "a@b.vn", "  ", "  dong 1\r\ndong 2\t \n", true);
        assertEquals("Nguyễn", r.contactName());
        assertEquals("dong 1\ndong 2", r.message());
        assertNull(r.contactPhone());
        assertEquals(ContactStatus.NEW, r.status());
        assertNull(r.closedAt());
        assertNull(r.piiAnonymizedAt());
        assertEquals(T0, r.createdAt());
        assertEquals(T0, r.consentAt());
        assertEquals(USER, r.customerUserId());
    }

    @Test
    void doDaiTinhTheoCodePointSauChuanHoa() {
        String emoji = "😀"; // 1 code point, 2 đơn vị UTF-16
        assertNotNull(submit("A", "a@b.vn", null, emoji.repeat(2000), true));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", "a@b.vn", null, emoji.repeat(2001), true)));
        // 2000 chữ tiếng Việt tổ hợp (NFD) chuẩn hóa còn 2000 code point NFC thì qua
        String composed = "ễ".repeat(2000);
        assertEquals(2000, submit("A", "a@b.vn", null, composed, true).message().codePointCount(0,
                submit("A", "a@b.vn", null, composed, true).message().length()));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("x".repeat(101), "a@b.vn", null, "m", true)));
        assertNotNull(submit("x".repeat(100), "a@b.vn", null, "m", true));
    }

    @Test
    void kyTuDieuKhienBiTuChoiTruNewlineVaTabTrongNoiDung() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A\u0007", "a@b.vn", null, "m", true)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", "a@b.vn", null, "m\u0000x", true)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A\nB", "a@b.vn", null, "m", true)));
        assertEquals("a\tb\nc", submit("A", "a@b.vn", null, "a\tb\nc", true).message());
    }

    @Test
    void truongBatBuocVaEmail() {
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit(" ", "a@b.vn", null, "m", true)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", "a@b.vn", null, "   ", true)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", null, null, "m", true)));
        for (String bad : List.of("khong-a-cong", "a@b", "a b@c.vn", "@b.vn", "a@.vn", "a@b..vn")) {
            assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", bad, null, "m", true)), bad);
        }
        String longMail = "a".repeat(250) + "@b.vn"; // 255 code point
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", longMail, null, "m", true)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> ShopContactRequest.submit(UUID.randomUUID(), TENANT,
                USER, null, "A", "a@b.vn", null, "m", true, T0)));
    }

    @Test
    void dienThoaiTuyChonDungDinhDangVaToiThieuSauChuSo() {
        assertNotNull(submit("A", "a@b.vn", "+84 (912) 345-678", "m", true));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", "a@b.vn", "12345", "m", true)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", "a@b.vn", "091234abc678", "m", true)));
        assertEquals(ErrorCode.INVALID_REQUEST, codeOf(() -> submit("A", "a@b.vn", "1".repeat(21), "m", true)));
        assertNotNull(submit("A", "a@b.vn", "1".repeat(20), "m", true));
    }

    @Test
    void consentPhaiLaTrue() {
        assertEquals(ErrorCode.CONSENT_REQUIRED, codeOf(() -> submit("A", "a@b.vn", null, "m", false)));
        assertEquals(ErrorCode.CONSENT_REQUIRED, codeOf(() -> submit("A", "a@b.vn", null, "m", null)));
    }

    @Test
    void thongDiepLoiKhongChuaGiaTriNguoiDungNhap() {
        SalesException e = assertThrows(SalesException.class,
                () -> submit("Ten-bi-mat", "email-sai-bi-mat", null, "noi-dung-bi-mat", true));
        assertFalse(e.getMessage().contains("email-sai-bi-mat"));
        assertFalse(e.getMessage().contains("Ten-bi-mat"));
        String s = ok().toString();
        assertFalse(s.contains("binh@example.vn") || s.contains("Bình") || s.contains("0912") || s.contains("giao"));
    }

    // ---- máy trạng thái ----

    @Test
    void mayTrangThaiMoiCapChuyen() {
        for (ContactStatus from : ContactStatus.values()) {
            for (ContactStatus to : ContactStatus.values()) {
                boolean allowed = (from == ContactStatus.NEW && to != ContactStatus.NEW)
                        || (from == ContactStatus.IN_PROGRESS && to == ContactStatus.CLOSED);
                assertEquals(allowed, from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }

    @Test
    void doiTrangThaiDatClosedAtKhiDongVaKhongMoLaiClosed() {
        Instant later = T0.plusSeconds(60);
        ShopContactRequest inProgress = ok().changeStatus(ContactStatus.IN_PROGRESS, later);
        assertNull(inProgress.closedAt());
        ShopContactRequest closed = inProgress.changeStatus(ContactStatus.CLOSED, later.plusSeconds(1));
        assertEquals(later.plusSeconds(1), closed.closedAt());
        assertEquals(ErrorCode.SHOP_CONTACT_STATE_CONFLICT,
                codeOf(() -> closed.changeStatus(ContactStatus.IN_PROGRESS, later)));
        assertEquals(ErrorCode.SHOP_CONTACT_STATE_CONFLICT,
                codeOf(() -> ok().changeStatus(ContactStatus.NEW, later)));
        assertEquals(ErrorCode.SHOP_CONTACT_STATE_CONFLICT, codeOf(() -> ok().changeStatus(null, later)));
        assertEquals(T0.plusSeconds(5), ok().changeStatus(ContactStatus.CLOSED, T0.plusSeconds(5)).closedAt());
    }

    // ---- ẩn danh ----

    @Test
    void anonymizeXoaDuNamCotVaGiuTrangThaiCungMocThoiGian() {
        ShopContactRequest closed = ok().changeStatus(ContactStatus.CLOSED, T0.plusSeconds(9));
        Instant at = T0.plusSeconds(1000);
        ShopContactRequest a = closed.anonymize(at);
        assertNull(a.customerUserId());
        assertNull(a.message());
        assertNull(a.contactName());
        assertNull(a.contactEmail());
        assertNull(a.contactPhone());
        assertEquals(at, a.piiAnonymizedAt());
        assertTrue(a.isAnonymized());
        assertEquals(ContactStatus.CLOSED, a.status());
        assertEquals(ContactTopic.ORDER, a.topic());
        assertEquals(closed.createdAt(), a.createdAt());
        assertEquals(closed.closedAt(), a.closedAt());
        // idempotent: gọi lại giữ nguyên mốc đầu tiên
        assertSame(a, a.anonymize(at.plusSeconds(5)));
        // vẫn đổi trạng thái được
        assertEquals(ContactStatus.IN_PROGRESS,
                ok().anonymize(at).changeStatus(ContactStatus.IN_PROGRESS, at).status());
    }

    // ---- ContactSubmissionPolicy ----

    private static ContactSubmissionPolicy policy() {
        return new ContactSubmissionPolicy(5, Duration.ofMinutes(10));
    }

    private static ContactSubmissionPolicy.Recent recent(ContactTopic t, String m, Instant at) {
        return new ContactSubmissionPolicy.Recent(t, m, at);
    }

    @Test
    void policyTrungTrongCuaSoThiBoQuaNgoaiCuaSoThiNhan() {
        var p = policy();
        assertEquals(ContactSubmissionPolicy.Decision.DUPLICATE, p.decide(
                List.of(recent(ContactTopic.ORDER, "xin chao", T0.minus(Duration.ofMinutes(9)))),
                ContactTopic.ORDER, "xin chao", T0));
        assertEquals(ContactSubmissionPolicy.Decision.ACCEPT, p.decide(
                List.of(recent(ContactTopic.ORDER, "xin chao", T0.minus(Duration.ofMinutes(11)))),
                ContactTopic.ORDER, "xin chao", T0));
        // đúng 10 phút: không còn trong cửa sổ (khoảng mở)
        assertEquals(ContactSubmissionPolicy.Decision.ACCEPT, p.decide(
                List.of(recent(ContactTopic.ORDER, "xin chao", T0.minus(Duration.ofMinutes(10)))),
                ContactTopic.ORDER, "xin chao", T0));
    }

    @Test
    void policyKhacTopicHoacKhacNoiDungKhongPhaiTrung() {
        var p = policy();
        Instant at = T0.minusSeconds(30);
        assertEquals(ContactSubmissionPolicy.Decision.ACCEPT,
                p.decide(List.of(recent(ContactTopic.PAYMENT, "xin chao", at)), ContactTopic.ORDER, "xin chao", T0));
        assertEquals(ContactSubmissionPolicy.Decision.ACCEPT,
                p.decide(List.of(recent(ContactTopic.ORDER, "xin chao!", at)), ContactTopic.ORDER, "xin chao", T0));
    }

    @Test
    void policyGioiHanNamTrong24GioTruot() {
        var p = policy();
        List<ContactSubmissionPolicy.Recent> four = List.of(
                recent(ContactTopic.OTHER, "1", T0.minus(Duration.ofHours(1))),
                recent(ContactTopic.OTHER, "2", T0.minus(Duration.ofHours(2))),
                recent(ContactTopic.OTHER, "3", T0.minus(Duration.ofHours(3))),
                recent(ContactTopic.OTHER, "4", T0.minus(Duration.ofHours(23))));
        assertEquals(ContactSubmissionPolicy.Decision.ACCEPT, p.decide(four, ContactTopic.OTHER, "moi", T0));
        List<ContactSubmissionPolicy.Recent> five = new java.util.ArrayList<>(four);
        five.add(recent(ContactTopic.OTHER, "5", T0.minus(Duration.ofHours(5))));
        assertEquals(ContactSubmissionPolicy.Decision.LIMIT_EXCEEDED, p.decide(five, ContactTopic.OTHER, "moi", T0));
        // dòng cũ hơn 24 giờ không tính
        List<ContactSubmissionPolicy.Recent> aged = new java.util.ArrayList<>(four);
        aged.add(recent(ContactTopic.OTHER, "5", T0.minus(Duration.ofHours(24).plusSeconds(1))));
        assertEquals(ContactSubmissionPolicy.Decision.ACCEPT, p.decide(aged, ContactTopic.OTHER, "moi", T0));
    }

    @Test
    void policyTrungXetTruocGioiHan() {
        var p = policy();
        List<ContactSubmissionPolicy.Recent> five = List.of(
                recent(ContactTopic.OTHER, "1", T0.minus(Duration.ofHours(1))),
                recent(ContactTopic.OTHER, "2", T0.minus(Duration.ofHours(2))),
                recent(ContactTopic.OTHER, "3", T0.minus(Duration.ofHours(3))),
                recent(ContactTopic.OTHER, "4", T0.minus(Duration.ofHours(4))),
                recent(ContactTopic.OTHER, "5", T0.minusSeconds(20)));
        assertEquals(ContactSubmissionPolicy.Decision.DUPLICATE, p.decide(five, ContactTopic.OTHER, "5", T0));
        assertEquals(ContactSubmissionPolicy.Decision.LIMIT_EXCEEDED, p.decide(five, ContactTopic.OTHER, "6", T0));
    }

    @Test
    void policyCauHinhSaiBiTuChoi() {
        assertThrows(IllegalArgumentException.class, () -> new ContactSubmissionPolicy(0, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new ContactSubmissionPolicy(5, null));
    }
}
