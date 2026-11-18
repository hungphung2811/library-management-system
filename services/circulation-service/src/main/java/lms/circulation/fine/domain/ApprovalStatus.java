package lms.circulation.fine.domain;

/** Trạng thái đề nghị cần người duyệt (miễn/giảm, hoàn khoản): chỉ chuyển PENDING sang APPROVED hoặc REJECTED một lần. */
public enum ApprovalStatus {
    PENDING, APPROVED, REJECTED
}
