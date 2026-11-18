package lms.circulation.fine.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Snapshot cách tính một khoản phạt hỏng/mất tại lúc lập, không đổi khi tenant sửa bậc hoặc chính sách sau đó: giá gốc,
 * nguồn giá thực dùng, tỉ lệ, mã/nhãn bậc hỏng (khoản mất không có bậc), số tiền trước khi kẹp sàn/trần, ghi chú và URL
 * ảnh bằng chứng. Khoản phạt trễ và khoản cũ không có snapshot (null).
 */
public record FineAssessment(BigDecimal basePrice, PriceSourceKind priceSource, boolean priceFallback, BigDecimal rate,
                             String damageLevelCode, String damageLevelLabel, BigDecimal amountBeforeClamp,
                             String note, List<String> evidenceUrls) {

    public static final int MAX_EVIDENCE_URLS = 3;
    public static final int MAX_URL_LENGTH = 500;
    public static final int MAX_NOTE_LENGTH = 1000;

    public FineAssessment {
        if (note != null) {
            note = note.strip();
            if (note.isEmpty()) {
                note = null;
            } else if (note.length() > MAX_NOTE_LENGTH) {
                throw FineRuleException.invalid("INVALID_DAMAGE_NOTE", "Ghi chú hỏng/mất tối đa " + MAX_NOTE_LENGTH + " ký tự");
            }
        }
        evidenceUrls = validateUrls(evidenceUrls);
    }

    /**
     * Tối đa 3 URL, mỗi URL http/https, không khoảng trắng, tối đa 500 ký tự. Chỉ kiểm cú pháp để lưu; hệ thống không
     * bao giờ tải nội dung từ URL (tránh SSRF).
     */
    public static List<String> validateUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) return List.of();
        if (urls.size() > MAX_EVIDENCE_URLS) {
            throw FineRuleException.invalid("INVALID_EVIDENCE_URL", "Tối đa " + MAX_EVIDENCE_URLS + " URL ảnh bằng chứng");
        }
        for (String url : urls) {
            if (url == null || url.length() > MAX_URL_LENGTH) {
                throw FineRuleException.invalid("INVALID_EVIDENCE_URL", "URL ảnh tối đa " + MAX_URL_LENGTH + " ký tự");
            }
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            boolean scheme = (lower.startsWith("http://") && lower.length() > 7)
                    || (lower.startsWith("https://") && lower.length() > 8);
            if (!scheme || url.codePoints().anyMatch(FineAssessment::forbiddenUrlChar)) {
                throw FineRuleException.invalid("INVALID_EVIDENCE_URL", "URL ảnh phải là http hoặc https hợp lệ: " + url);
            }
        }
        return List.copyOf(urls);
    }

    private static final String FORBIDDEN_URL_CHARS = "\"<>`\\^{}|";

    /** Ký tự không được có trong URL: khoảng trắng/điều khiển/định dạng Unicode (NBSP, ZWSP...) và ký tự HTML/không an toàn. */
    private static boolean forbiddenUrlChar(int c) {
        if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) return true;
        int type = Character.getType(c);
        if (type == Character.FORMAT || type == Character.CONTROL || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SPACE_SEPARATOR) return true;
        return c < 128 && FORBIDDEN_URL_CHARS.indexOf(c) >= 0;
    }
}
