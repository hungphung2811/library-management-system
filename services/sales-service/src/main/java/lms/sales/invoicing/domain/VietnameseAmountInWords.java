package lms.sales.invoicing.domain;

/**
 * Đọc số tiền đồng bằng chữ tiếng Việt (hàm thuần, ADR-0030 mục D1.7). Quy tắc: nhóm ba chữ số với đơn vị nghìn, triệu,
 * tỷ, nghìn tỷ, triệu tỷ, tỷ tỷ; "linh" (không dùng "lẻ") cho hàng chục bằng 0 khi có hàng cao hơn; "không trăm" khi hàng
 * trăm bằng 0 mà còn nhóm cao hơn; "mười", "mươi"; "mốt" cho 21, 31, ... 91; "tư" cho 24, 34, ... 94 (14 vẫn đọc
 * "mười bốn"); "lăm" cho chữ số 5 ở hàng đơn vị khi hàng chục khác 0 ("mười lăm", "hai mươi lăm"). Hậu tố "đồng", viết
 * hoa chữ cái đầu. 0 đọc là "Không đồng".
 */
public final class VietnameseAmountInWords {

    private static final String[] DIGITS = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};
    private static final String[] SCALES = {"", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ", "tỷ tỷ"};

    private VietnameseAmountInWords() {
    }

    /** Số tiền (không âm) bằng chữ, ví dụ 2_500_000 là "Hai triệu năm trăm nghìn đồng". */
    public static String of(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Số tiền không được âm");
        }
        if (amount == 0) {
            return "Không đồng";
        }
        int[] groups = new int[SCALES.length];
        long rest = amount;
        int highest = -1;
        for (int i = 0; i < groups.length && rest > 0; i++) {
            groups[i] = (int) (rest % 1000);
            rest /= 1000;
            if (groups[i] != 0) {
                highest = i;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = highest; i >= 0; i--) {
            if (groups[i] == 0) {
                continue;
            }
            boolean hasHigher = i < highest;
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(readGroup(groups[i], hasHigher));
            if (!SCALES[i].isEmpty()) {
                out.append(' ').append(SCALES[i]);
            }
        }
        out.append(" đồng");
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }

    private static String readGroup(int value, boolean hasHigher) {
        int h = value / 100;
        int t = (value / 10) % 10;
        int u = value % 10;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(DIGITS[h]).append(" trăm");
        } else if (hasHigher) {
            sb.append("không trăm");
        }
        if (t == 0) {
            if (u != 0) {
                if (sb.length() > 0) {
                    sb.append(" linh ");
                }
                sb.append(DIGITS[u]);
            }
        } else if (t == 1) {
            appendWord(sb, "mười");
            if (u == 5) {
                sb.append(" lăm");
            } else if (u != 0) {
                sb.append(' ').append(DIGITS[u]);
            }
        } else {
            appendWord(sb, DIGITS[t] + " mươi");
            if (u == 1) {
                sb.append(" mốt");
            } else if (u == 4) {
                sb.append(" tư");
            } else if (u == 5) {
                sb.append(" lăm");
            } else if (u != 0) {
                sb.append(' ').append(DIGITS[u]);
            }
        }
        return sb.toString();
    }

    private static void appendWord(StringBuilder sb, String word) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(word);
    }
}
