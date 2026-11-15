package lms.identity.user.application;

import lms.identity.user.domain.ReaderType;
import java.time.LocalDate;
import java.util.UUID;

/**
 * temporaryPassword có giá trị: tạo luôn tài khoản online cho độc giả (credential mật khẩu tạm buộc đổi, hồ sơ users và
 * membership ở tenant hiện tại); username null thì dùng số thẻ.
 */
public record IssueReaderCardCommand(UUID userId, String cardNo, String fullName, String email,
                                     ReaderType readerType, LocalDate cardExpiryDate,
                                     String username, String temporaryPassword) {

    /** Cấp thẻ cho tài khoản đã có (hoặc độc giả chỉ dùng tại quầy), không tạo tài khoản online. */
    public IssueReaderCardCommand(UUID userId, String cardNo, String fullName, String email,
                                  ReaderType readerType, LocalDate cardExpiryDate) {
        this(userId, cardNo, fullName, email, readerType, cardExpiryDate, null, null);
    }
}
