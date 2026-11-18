package lms.identity.user.application;

import lms.identity.user.domain.Gender;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderStatus;
import lms.identity.user.domain.ReaderType;
import java.time.LocalDate;
import java.util.UUID;

/**
 * cardValid là kết quả kiểm tra QĐ2 tại thời điểm gọi; service khác dùng cờ này thay vì tự tính. Các trường từ phone
 * trở xuống là thông tin cá nhân/học vụ tùy chọn (có thể null).
 */
public record ReaderProfileResponse(UUID userId, String cardNo, String fullName, String email,
                                    ReaderType readerType, LocalDate cardExpiryDate, ReaderStatus status,
                                    boolean cardValid,
                                    String phone, LocalDate dateOfBirth, Gender gender, String address,
                                    String faculty, String className, String memberCode, LocalDate cardIssuedDate) {

    public ReaderProfileResponse(UUID userId, String cardNo, String fullName, String email,
                                 ReaderType readerType, LocalDate cardExpiryDate, ReaderStatus status,
                                 boolean cardValid) {
        this(userId, cardNo, fullName, email, readerType, cardExpiryDate, status, cardValid,
                null, null, null, null, null, null, null, null);
    }

    public static ReaderProfileResponse from(ReaderProfile p, LocalDate today) {
        ReaderPersonalInfo i = p.personalInfo();
        return new ReaderProfileResponse(p.userId(), p.cardNo(), p.fullName(), p.email(), p.readerType(),
                p.cardExpiryDate(), p.status(), p.isCardValid(today), i.phone(), i.dateOfBirth(), i.gender(),
                i.address(), i.faculty(), i.className(), i.memberCode(), i.cardIssuedDate());
    }
}
