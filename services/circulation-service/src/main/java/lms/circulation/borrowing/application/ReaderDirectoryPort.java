package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.ReaderType;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng sang identity-service: thông tin độc giả theo userId (thủ thư lập phiếu cho độc giả khác) gồm loại độc giả
 * và cờ cardValid (thẻ còn hạn và tài khoản không khóa, QĐ2). Adapter chuyển tiếp token của thủ thư.
 */
public interface ReaderDirectoryPort {

    Optional<ReaderInfo> find(UUID readerId);

    record ReaderInfo(UUID userId, ReaderType readerType, boolean cardValid) {
    }
}
