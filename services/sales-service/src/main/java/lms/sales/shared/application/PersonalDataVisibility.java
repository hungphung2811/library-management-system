package lms.sales.shared.application;

import lms.sales.shared.domain.PersonalDataMasker;

/**
 * Chính sách hiển thị dữ liệu cá nhân ở API dành cho nhân viên (đơn, liên hệ). Mặc định CHE (an toàn); cấu hình
 * {@code lms.privacy.mask-personal-data=false} (chỉ nên dùng ở dev) trả giá trị gốc. Đây là cấu hình theo môi
 * trường, không thay thế ẩn danh theo thời hạn (pii_anonymized_at) và không áp cho log/audit/Kafka (các nơi này
 * không bao giờ nhận dữ liệu cá nhân). Giá trị được đặt một lần lúc khởi động bởi SharedBeanConfiguration.
 */
public final class PersonalDataVisibility {

    private static volatile boolean masking = true;

    private PersonalDataVisibility() {
    }

    public static void configure(boolean maskPersonalData) {
        masking = maskPersonalData;
    }

    public static boolean isMasking() {
        return masking;
    }

    public static String name(String value) {
        return masking ? PersonalDataMasker.maskName(value) : blankToNull(value);
    }

    public static String phone(String value) {
        return masking ? PersonalDataMasker.maskPhone(value) : blankToNull(value);
    }

    public static String email(String value) {
        return masking ? PersonalDataMasker.maskEmail(value) : blankToNull(value);
    }

    public static String address(String value) {
        return masking ? PersonalDataMasker.maskAddress(value) : blankToNull(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
