package lms.circulation.fine.application;

import java.util.UUID;

/**
 * Cổng đưa bản sao đã tìm lại từ LOST về AVAILABLE hoặc DAMAGED ở catalog (chủ sở hữu trạng thái bản sao). Idempotent;
 * lỗi thì ném ngoại lệ để use case duyệt hoàn khoản rollback toàn bộ.
 */
public interface CopyRestorePort {

    void restore(UUID bookCopyId, Target target);

    enum Target { AVAILABLE, DAMAGED }
}
