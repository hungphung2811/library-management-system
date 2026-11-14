package lms.identity.user.application;

import lms.identity.user.domain.ReaderProfile;
import lms.identity.user.domain.ReaderStatus;
import lms.identity.user.domain.ReaderType;
import java.time.LocalDate;
import java.util.UUID;

/** cardValid là kết quả kiểm tra QĐ2 tại thời điểm gọi; service khác dùng cờ này thay vì tự tính. */
public record ReaderProfileResponse(UUID userId, String cardNo, String fullName, String email,
                                    ReaderType readerType, LocalDate cardExpiryDate, ReaderStatus status,
                                    boolean cardValid) {

    public static ReaderProfileResponse from(ReaderProfile p, LocalDate today) {
        return new ReaderProfileResponse(p.userId(), p.cardNo(), p.fullName(), p.email(), p.readerType(),
                p.cardExpiryDate(), p.status(), p.isCardValid(today));
    }
}
