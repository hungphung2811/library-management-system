package lms.sales.contact.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Chống spam theo người gửi (ADR-0015 R4, R5). Đầu vào là các yêu cầu GẦN ĐÂY của chính người gửi. Trùng nội dung
 * (cùng topic và message đã chuẩn hóa trong cửa sổ ngắn) xét TRƯỚC giới hạn số lượng: bấm hai lần ở yêu cầu cuối
 * vẫn được coi là gửi lại, không phải vượt giới hạn.
 */
public final class ContactSubmissionPolicy {

    public static final Duration LIMIT_WINDOW = Duration.ofHours(24);

    public enum Decision { ACCEPT, DUPLICATE, LIMIT_EXCEEDED }

    /** Một lần gửi trước đó của cùng người (chỉ các trường cần so). */
    public record Recent(ContactTopic topic, String message, Instant createdAt) {
        @Override
        public String toString() {
            return "Recent[***]";
        }
    }

    private final int maxPerWindow;
    private final Duration duplicateWindow;

    public ContactSubmissionPolicy(int maxPerWindow, Duration duplicateWindow) {
        if (maxPerWindow < 1 || duplicateWindow == null || duplicateWindow.isNegative()) {
            throw new IllegalArgumentException("Cấu hình chống spam liên hệ không hợp lệ");
        }
        this.maxPerWindow = maxPerWindow;
        this.duplicateWindow = duplicateWindow;
    }

    public Decision decide(List<Recent> recent, ContactTopic topic, String message, Instant now) {
        Instant dupSince = now.minus(duplicateWindow);
        Instant limitSince = now.minus(LIMIT_WINDOW);
        long inWindow = 0;
        for (Recent r : recent) {
            if (r.createdAt().isAfter(dupSince) && r.topic() == topic && r.message() != null
                    && r.message().equals(message)) {
                return Decision.DUPLICATE;
            }
            if (r.createdAt().isAfter(limitSince)) {
                inWindow++;
            }
        }
        return inWindow >= maxPerWindow ? Decision.LIMIT_EXCEEDED : Decision.ACCEPT;
    }
}
