package lms.support.notification.application;

import lms.support.notification.domain.NotificationChannel;

/**
 * Cổng gửi theo kênh (ADR-0030 D4). Thêm kênh mới = thêm một bean cài đặt cổng này, không sửa use case.
 * Lỗi tạm thời ném {@link TransientSendException} (thử lại), lỗi vĩnh viễn ném {@link PermanentSendException}.
 */
public interface NotificationProvider {

    NotificationChannel channel();

    SendResult send(RenderedMessage message, ResolvedAddress address);

    /** Nội dung đã dựng (văn bản thuần). */
    record RenderedMessage(String subject, String body) {
    }

    /** Địa chỉ đã tra: address chỉ sống trong bộ nhớ lúc gửi; senderName là tên hiển thị của người gửi (tên tenant). */
    record ResolvedAddress(String address, String recipientName, String senderName) {
        @Override
        public String toString() {
            return "ResolvedAddress[***]";
        }
    }

    record SendResult(String providerMessageId) {
    }

    class TransientSendException extends RuntimeException {
        private final String code;

        public TransientSendException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    class PermanentSendException extends RuntimeException {
        private final String code;

        public PermanentSendException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
