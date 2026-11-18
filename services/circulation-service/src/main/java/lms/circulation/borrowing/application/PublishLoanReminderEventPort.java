package lms.circulation.borrowing.application;

import lms.common.kernel.DomainEvent;

/**
 * Phát sự kiện nhắc hạn ĐỒNG BỘ: trả về khi broker đã nhận, ném lỗi nếu không (ADR-0030 D11: phát lỗi thì rollback lô,
 * mai thử lại; khác với publish best-effort của các sự kiện nghiệp vụ khác).
 */
public interface PublishLoanReminderEventPort {

    void publishAndWait(DomainEvent event);
}
