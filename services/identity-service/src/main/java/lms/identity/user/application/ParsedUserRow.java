package lms.identity.user.application;

import java.time.LocalDate;
import lms.identity.user.domain.ReaderPersonalInfo;
import lms.identity.user.domain.ReaderType;
import lms.identity.user.domain.UserPersonalInfo;

/**
 * Một dòng nhập đã qua kiểm tra bằng domain (User, UserPersonalInfo, ReaderProfile). readerType/cardExpiryDate/readerInfo
 * chỉ có nghĩa khi {@code reader}. Dữ liệu cá nhân: không log.
 */
public record ParsedUserRow(String username, String fullName, String email, UserPersonalInfo personal, boolean reader,
                     ReaderType readerType, LocalDate cardExpiryDate, ReaderPersonalInfo readerInfo) {
}
