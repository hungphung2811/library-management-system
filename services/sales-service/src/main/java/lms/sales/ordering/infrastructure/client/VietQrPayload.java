package lms.sales.ordering.infrastructure.client;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Dựng chuỗi VietQR (NAPAS 247, nền EMVCo Merchant-Presented QR): các trường TLV ghép liền (tag 2 ký tự, độ dài 2 chữ
 * số, giá trị), kết thúc bằng tag 63 là CRC16/CCITT-FALSE (đa thức 0x1021, khởi tạo 0xFFFF) tính trên toàn bộ chuỗi trước
 * đó cộng "6304". Dùng QR động (tag 01 = 12) kèm số tiền và nội dung chuyển khoản (tag 62/08) là mã tham chiếu.
 * Chuỗi chỉ gồm ASCII; tên tài khoản được bỏ dấu.
 */
public final class VietQrPayload {

    static final String NAPAS_AID = "A000000727";
    static final String SERVICE_TO_ACCOUNT = "QRIBFTTA";
    static final int MAX_REFERENCE = 25;
    static final int MAX_NAME = 25;

    private VietQrPayload() {
    }

    /**
     * @param bankBin   mã BIN ngân hàng thụ hưởng (6 chữ số)
     * @param accountNo số tài khoản thụ hưởng
     * @param amount    số tiền VND (số nguyên dương)
     * @param reference nội dung chuyển khoản (tối đa 25 ký tự)
     * @param accountName tên người thụ hưởng, có thể rỗng
     */
    public static String build(String bankBin, String accountNo, long amount, String reference, String accountName) {
        if (bankBin == null || bankBin.isBlank() || accountNo == null || accountNo.isBlank()) {
            throw new IllegalArgumentException("Thiếu BIN ngân hàng hoặc số tài khoản");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Số tiền phải lớn hơn 0");
        }
        if (reference == null || reference.isBlank() || reference.length() > MAX_REFERENCE) {
            throw new IllegalArgumentException("Nội dung chuyển khoản phải từ 1 đến " + MAX_REFERENCE + " ký tự");
        }
        String beneficiary = tlv("00", bankBin.trim()) + tlv("01", accountNo.trim());
        String merchantInfo = tlv("00", NAPAS_AID) + tlv("01", beneficiary) + tlv("02", SERVICE_TO_ACCOUNT);
        StringBuilder sb = new StringBuilder();
        sb.append(tlv("00", "01"));
        sb.append(tlv("01", "12"));
        sb.append(tlv("38", merchantInfo));
        sb.append(tlv("53", "704"));
        sb.append(tlv("54", Long.toString(amount)));
        sb.append(tlv("58", "VN"));
        String name = asciiUpper(accountName);
        if (!name.isEmpty()) {
            sb.append(tlv("59", name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name));
        }
        sb.append(tlv("62", tlv("08", reference.trim())));
        sb.append("6304");
        sb.append(crc16(sb.toString()));
        return sb.toString();
    }

    static String tlv(String tag, String value) {
        if (value.length() > 99) {
            throw new IllegalArgumentException("Giá trị tag " + tag + " quá dài");
        }
        return tag + String.format("%02d", value.length()) + value;
    }

    /** CRC16/CCITT-FALSE, trả 4 chữ số hex in hoa. */
    public static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }

    private static String asciiUpper(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return n.replaceAll("[^A-Za-z0-9 ]", "").toUpperCase(Locale.ROOT).trim();
    }
}
