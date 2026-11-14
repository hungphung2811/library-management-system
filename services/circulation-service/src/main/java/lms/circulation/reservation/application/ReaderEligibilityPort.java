package lms.circulation.reservation.application;

import java.util.UUID;

/**
 * Cổng sang identity-service: độc giả có đủ điều kiện theo QĐ2 không. Adapter hỏi bằng chính token của người gọi
 * nên chỉ trả lời được cho độc giả đang đăng nhập; readerId khác chủ token được coi là không đủ điều kiện.
 */
public interface ReaderEligibilityPort {

    ReaderEligibility check(UUID readerId);

    record ReaderEligibility(boolean hasCard, boolean cardValid) {
    }
}
