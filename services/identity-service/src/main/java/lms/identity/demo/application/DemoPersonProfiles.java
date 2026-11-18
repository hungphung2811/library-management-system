package lms.identity.demo.application;

import java.time.LocalDate;
import lms.demo.plan.DemoPlan.ReaderPlan;
import lms.demo.plan.DemoPlan.StaffPlan;
import lms.identity.user.domain.Gender;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.UserPersonalInfo;

/**
 * Thông tin cá nhân giả (hợp lệ, tất định theo chỉ số) cho tài khoản demo (ADR-0018). Số điện thoại dùng đầu số di động
 * giả, không phải số thật; địa chỉ chỉ ở mức đường/phường tượng trưng. Ngày sinh là ngày tuyệt đối trong quá khứ nên
 * không phụ thuộc ngày chạy seed.
 */
final class DemoPersonProfiles {

    private static final String[] ADDRESSES = {
            "12 Nguyễn Trãi, Thanh Xuân, Hà Nội",
            "45 Lê Lợi, Quận 1, TP. Hồ Chí Minh",
            "88 Trần Phú, Hải Châu, Đà Nẵng",
            "23 Hùng Vương, Ninh Kiều, Cần Thơ",
            "7 Điện Biên Phủ, Hồng Bàng, Hải Phòng",
            "150 Nguyễn Huệ, TP. Huế, Thừa Thiên Huế",
            "34 Trần Hưng Đạo, Nha Trang, Khánh Hòa",
            "61 Phan Bội Châu, Vinh, Nghệ An",
            "19 Lý Thường Kiệt, Biên Hòa, Đồng Nai",
            "9 Quang Trung, Thanh Hóa, Thanh Hóa",
            "27 Nguyễn Văn Cừ, Quy Nhơn, Bình Định",
            "102 Hai Bà Trưng, Đà Lạt, Lâm Đồng"};
    private static final String[] FACULTIES = {"Khoa Công nghệ thông tin", "Khoa Kinh tế", "Khoa Ngoại ngữ",
            "Khoa Khoa học xã hội và nhân văn", "Khoa Điện - Điện tử", "Khoa Quản trị kinh doanh"};
    private static final String[] CLASS_PREFIX = {"CNTT", "KT", "NN", "XH", "DDT", "QTKD"};
    private static final String[] LECTURER_UNITS = {"Bộ môn Kỹ thuật phần mềm", "Bộ môn Kế toán", "Bộ môn Tiếng Anh",
            "Bộ môn Văn học", "Bộ môn Tự động hóa", "Bộ môn Marketing"};

    private DemoPersonProfiles() {
    }

    static UserPersonalInfo staff(StaffPlan s) {
        int i = s.index();
        String title = switch (s.role()) {
            case TENANT_ADMIN -> "Quản trị viên hệ thống";
            case LIBRARIAN -> "Thủ thư";
            case STORE_MANAGER -> "Quản lý cửa hàng sách";
        };
        Gender gender = s.role() == lms.demo.plan.DemoPlan.StaffRole.LIBRARIAN ? Gender.FEMALE : Gender.MALE;
        return new UserPersonalInfo(phone(i), LocalDate.of(1980 + i * 3, 3 + i * 2, 10 + i * 4), gender,
                ADDRESSES[i % ADDRESSES.length], null, title, String.format("NV%03d", i + 1));
    }

    /** Họ tên độc giả demo xen kẽ: chỉ số chẵn nam, lẻ nữ (xem DemoPlanGenerator). */
    static ReaderPersonalInfo reader(ReaderPlan r, boolean lecturer, LocalDate today) {
        int i = r.index();
        Gender gender = i % 2 == 0 ? Gender.MALE : Gender.FEMALE;
        int group = i % FACULTIES.length;
        LocalDate issued = today.minusMonths(1 + i % 10);
        if (lecturer) {
            return new ReaderPersonalInfo(phone(10 + i), LocalDate.of(1976 + i % 14, 1 + i % 12, 5 + i % 20), gender,
                    ADDRESSES[(i + 3) % ADDRESSES.length], LECTURER_UNITS[group], null,
                    String.format("CB%05d", 1200 + i), issued);
        }
        int year = 2002 + i % 4;
        return new ReaderPersonalInfo(phone(10 + i), LocalDate.of(year, 1 + (i * 5) % 12, 3 + (i * 7) % 25), gender,
                ADDRESSES[(i + 3) % ADDRESSES.length], FACULTIES[group],
                String.format("%s-K%d%s", CLASS_PREFIX[group], 22 + (2005 - year), i % 2 == 0 ? "A" : "B"),
                String.format("SV%d%04d", 21 + (2005 - year), 1000 + i * 37), issued);
    }

    private static String phone(int n) {
        return String.format("09%02d %03d %03d", 10 + n % 80, 100 + n * 13 % 900, 100 + n * 29 % 900);
    }
}
