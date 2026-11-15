package lms.circulation.borrowing.domain;

/** Trạng thái bản sao do catalog sở hữu (docs/requirements.md mục 8); borrowing chỉ đọc để kiểm tra và yêu cầu đổi. */
public enum CopyStatus {
    AVAILABLE, LOANED, RESERVED, LOST, DAMAGED
}
