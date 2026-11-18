package lms.identity.user.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.UUID;
import lms.identity.user.domain.Gender;
import lms.identity.user.domain.InvalidReaderException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderStatus;
import lms.identity.user.domain.ReaderType;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserPersonalInfo;
import org.junit.jupiter.api.Test;

/** Quy tắc thông tin cá nhân tùy chọn của người dùng và độc giả (domain thuần). */
class PersonalInfoTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    static UserPersonalInfo user(String phone, LocalDate dob, String address, String avatar, String job, String code) {
        return UserPersonalInfo.of(phone, dob, Gender.MALE, address, avatar, job, code, TODAY);
    }

    static ReaderPersonalInfo reader(String phone, LocalDate dob, String faculty, String className, String code,
                                     LocalDate issued) {
        return ReaderPersonalInfo.of(phone, dob, null, null, faculty, className, code, issued, TODAY);
    }

    @Test
    void taoThongTinNguoiDungHopLeChuanHoaChuoiRong() {
        UserPersonalInfo i = user(" 0912 345-678 ", LocalDate.of(1990, 1, 2), "  ", "https://cdn.x/a.png", "", "NV01");
        assertEquals("0912 345-678", i.phone());
        assertNull(i.address());
        assertNull(i.jobTitle());
        assertEquals("NV01", i.employeeCode());
        assertTrue(UserPersonalInfo.EMPTY.isEmpty());
    }

    @Test
    void tuChoiGiaTriSai() {
        assertThrows(InvalidUserException.class, () -> user("09x", null, null, null, null, null));
        assertThrows(InvalidUserException.class, () -> user("1".repeat(21), null, null, null, null, null));
        assertThrows(InvalidUserException.class, () -> user(null, TODAY.plusDays(1), null, null, null, null));
        assertThrows(InvalidUserException.class, () -> user(null, TODAY.minusYears(121), null, null, null, null));
        assertThrows(InvalidUserException.class, () -> user(null, null, "a".repeat(501), null, null, null));
        assertThrows(InvalidUserException.class, () -> user(null, null, null, "ftp://x/y", null, null));
        assertThrows(InvalidUserException.class, () -> user(null, null, null, "https://x/" + "a".repeat(500), null, null));
        assertThrows(InvalidUserException.class, () -> user(null, null, null, null, "j".repeat(101), null));
        assertThrows(InvalidUserException.class, () -> user(null, null, null, null, null, "c".repeat(33)));
        // biên hợp lệ: đúng 120 tuổi và đúng hôm nay
        user(null, TODAY.minusYears(120), null, null, null, null);
        user(null, TODAY, null, null, null, null);
    }

    @Test
    void nguoiDungGopVaChiDienTruongConThieu() {
        User u = User.create(UUID.randomUUID(), "alice", "Alice", "a@x.vn");
        assertTrue(u.personalInfo().isEmpty());

        assertTrue(u.mergePersonalInfo(user("0911", null, "Hà Nội", null, null, null)));
        // gộp: trường null của patch giữ nguyên, trường có giá trị ghi đè
        assertTrue(u.mergePersonalInfo(UserPersonalInfo.of(null, null, null, "TP.HCM", null, "Thủ thư", null, TODAY)));
        assertEquals("0911", u.personalInfo().phone());
        assertEquals("TP.HCM", u.personalInfo().address());
        assertFalse(u.mergePersonalInfo(UserPersonalInfo.EMPTY));

        // fillMissing: không đè giá trị đã có, chỉ điền chỗ trống
        assertTrue(u.fillMissingPersonalInfo(user("0999", LocalDate.of(1985, 5, 5), "khác", null, "khác", "NV9")));
        assertEquals("0911", u.personalInfo().phone());
        assertEquals("TP.HCM", u.personalInfo().address());
        assertEquals("Thủ thư", u.personalInfo().jobTitle());
        assertEquals(LocalDate.of(1985, 5, 5), u.personalInfo().dateOfBirth());
        assertEquals("NV9", u.personalInfo().employeeCode());
        assertFalse(u.fillMissingPersonalInfo(user("0000", null, null, null, null, null)));
    }

    @Test
    void thongTinDocGiaHopLeVaGioiHan() {
        ReaderPersonalInfo ok = reader("0912 345 678", LocalDate.of(2003, 5, 20), "Khoa Kinh tế", "KT-K22A",
                "SV2100123", LocalDate.of(2026, 9, 1));
        assertEquals("Khoa Kinh tế", ok.faculty());
        assertThrows(InvalidReaderException.class, () -> reader("abc", null, null, null, null, null));
        assertThrows(InvalidReaderException.class, () -> reader(null, null, "f".repeat(151), null, null, null));
        assertThrows(InvalidReaderException.class, () -> reader(null, null, null, "c".repeat(51), null, null));
        assertThrows(InvalidReaderException.class, () -> reader(null, null, null, null, "m".repeat(33), null));
        assertThrows(InvalidReaderException.class, () -> reader(null, TODAY.plusDays(1), null, null, null, null));
    }

    @Test
    void ngayCapTheKhongDuocSauHanThe() {
        LocalDate expiry = TODAY.plusYears(1);
        UUID id = UUID.randomUUID();
        ReaderPersonalInfo late = reader(null, null, null, null, null, expiry.plusDays(1));
        assertThrows(InvalidReaderException.class,
                () -> ReaderProfile.issue(id, "SV1", "A", "a@x.vn", ReaderType.STUDENT, expiry, TODAY, late));
        ReaderProfile p = ReaderProfile.issue(id, "SV1", "A", "a@x.vn", ReaderType.STUDENT, expiry, TODAY,
                reader(null, null, null, null, null, expiry));
        assertEquals(expiry, p.personalInfo().cardIssuedDate());
        // thẻ cũ (không có thông tin) vẫn dựng được và có hồ sơ rỗng
        assertTrue(ReaderProfile.restore(id, "SV1", "A", "a@x.vn", ReaderType.STUDENT, expiry, ReaderStatus.ACTIVE)
                .personalInfo().isEmpty());
    }

    @Test
    void docGiaFillMissingKhongDeGiaTriCo() {
        ReaderProfile p = ReaderProfile.issue(UUID.randomUUID(), "SV1", "A", "a@x.vn", ReaderType.STUDENT,
                TODAY.plusYears(1), TODAY, reader("0911", null, "Khoa A", null, null, null));
        assertTrue(p.fillMissingPersonalInfo(reader("0999", null, "Khoa B", "Lớp 1", "SV9", null)));
        assertEquals("0911", p.personalInfo().phone());
        assertEquals("Khoa A", p.personalInfo().faculty());
        assertEquals("Lớp 1", p.personalInfo().className());
        assertEquals("SV9", p.personalInfo().memberCode());
        assertFalse(p.fillMissingPersonalInfo(reader("0000", null, "Khoa C", null, null, null)));
    }
}
