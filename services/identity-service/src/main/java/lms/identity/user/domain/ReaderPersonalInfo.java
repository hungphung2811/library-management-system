package lms.identity.user.domain;

import java.time.LocalDate;

/**
 * Thông tin cá nhân/học vụ tùy chọn của độc giả (trong tenant). Dữ liệu cá nhân: không ghi vào log, audit hay event.
 * faculty: khoa/đơn vị; className: lớp/ngành; memberCode: mã sinh viên/mã cán bộ.
 */
public record ReaderPersonalInfo(String phone, LocalDate dateOfBirth, Gender gender, String address, String faculty,
                                 String className, String memberCode, LocalDate cardIssuedDate) {

    public static final ReaderPersonalInfo EMPTY = new ReaderPersonalInfo(null, null, null, null, null, null, null, null);

    public static ReaderPersonalInfo of(String phone, LocalDate dateOfBirth, Gender gender, String address,
                                        String faculty, String className, String memberCode, LocalDate cardIssuedDate,
                                        LocalDate today) {
        try {
            return new ReaderPersonalInfo(PersonalRules.phone(phone), PersonalRules.dateOfBirth(dateOfBirth, today),
                    gender, PersonalRules.text(address, 500, "Địa chỉ"), PersonalRules.text(faculty, 150, "Khoa/đơn vị"),
                    PersonalRules.text(className, 50, "Lớp/ngành"),
                    PersonalRules.text(memberCode, 32, "Mã sinh viên/cán bộ"), cardIssuedDate);
        } catch (IllegalArgumentException e) {
            throw new InvalidReaderException(e.getMessage());
        }
    }

    public boolean isEmpty() {
        return this.equals(EMPTY);
    }

    public ReaderPersonalInfo merge(ReaderPersonalInfo patch) {
        return new ReaderPersonalInfo(pick(patch.phone, phone), pick(patch.dateOfBirth, dateOfBirth),
                pick(patch.gender, gender), pick(patch.address, address), pick(patch.faculty, faculty),
                pick(patch.className, className), pick(patch.memberCode, memberCode),
                pick(patch.cardIssuedDate, cardIssuedDate));
    }

    public ReaderPersonalInfo fillMissing(ReaderPersonalInfo defaults) {
        return defaults.merge(this);
    }

    private static <T> T pick(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
