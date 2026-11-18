package lms.sales.ordering.application;

import java.security.SecureRandom;

/** Sinh mã tham chiếu thanh toán: "LMS" + 10 ký tự in hoa/số (bỏ ký tự dễ nhầm), đủ ngắn để làm nội dung chuyển khoản. */
final class PaymentReferences {

    static final String PREFIX = "LMS";
    static final int RANDOM_LENGTH = 10;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PaymentReferences() {
    }

    static String next() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
