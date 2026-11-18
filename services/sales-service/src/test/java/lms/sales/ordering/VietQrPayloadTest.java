package lms.sales.ordering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import lms.sales.ordering.infrastructure.client.VietQrPayload;
import org.junit.jupiter.api.Test;

/** Chuỗi VietQR/EMVCo do cổng giả lập sinh ra: TLV hợp lệ, CRC16 đúng, số tiền và nội dung chuyển khoản đúng. */
class VietQrPayloadTest {

    /** Tách TLV mức cao nhất: tag (2 ký tự) + độ dài (2 chữ số) + giá trị; phải hết chuỗi đúng khít. */
    private static Map<String, String> parse(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < s.length()) {
            String tag = s.substring(i, i + 2);
            int len = Integer.parseInt(s.substring(i + 2, i + 4));
            String value = s.substring(i + 4, i + 4 + len);
            out.put(tag, value);
            i += 4 + len;
        }
        assertEquals(s.length(), i, "độ dài các trường phải khớp đúng chuỗi");
        return out;
    }

    @Test
    void crc16CcittFalseDungVectorChuan() {
        // Vector chuẩn của CRC-16/CCITT-FALSE: "123456789" -> 0x29B1
        assertEquals("29B1", VietQrPayload.crc16("123456789"));
    }

    @Test
    void chuoiVietQrCoCauTrucVaCrcDung() {
        String qr = VietQrPayload.build("970422", "0123456789", 215_000, "LMSABCDE2345", "Cửa hàng Sách Việt");

        assertTrue(qr.startsWith("000201010212"), "payload format 01, QR động (12)");
        Map<String, String> top = parse(qr);
        assertEquals("704", top.get("53"));
        assertEquals("215000", top.get("54"));
        assertEquals("VN", top.get("58"));
        assertEquals("CUA HANG SACH VIET", top.get("59"), "tên bỏ dấu, in hoa");
        assertEquals("08" + "12" + "LMSABCDE2345", top.get("62"), "nội dung chuyển khoản = mã tham chiếu");

        Map<String, String> merchant = parse(top.get("38"));
        assertEquals("A000000727", merchant.get("00"));
        assertEquals("QRIBFTTA", merchant.get("02"));
        Map<String, String> beneficiary = parse(merchant.get("01"));
        assertEquals("970422", beneficiary.get("00"));
        assertEquals("0123456789", beneficiary.get("01"));

        // CRC: tag 63, dài 04, tính trên toàn bộ phần trước giá trị CRC (gồm "6304")
        assertTrue(qr.matches(".*6304[0-9A-F]{4}$"));
        String body = qr.substring(0, qr.length() - 4);
        assertEquals(qr.substring(qr.length() - 4), VietQrPayload.crc16(body));
        assertEquals(qr.substring(qr.length() - 4), top.get("63"));
    }

    @Test
    void duLieuSaiBiTuChoi() {
        assertThrows(IllegalArgumentException.class, () -> VietQrPayload.build("970422", "1", 0, "LMS1", null));
        assertThrows(IllegalArgumentException.class, () -> VietQrPayload.build("", "1", 1000, "LMS1", null));
        assertThrows(IllegalArgumentException.class,
                () -> VietQrPayload.build("970422", "1", 1000, "X".repeat(26), null));
    }

    @Test
    void khongCoTenThiBoQuaTag59() {
        Map<String, String> top = parse(VietQrPayload.build("970422", "1", 1000, "LMS1", null));
        assertTrue(!top.containsKey("59"));
    }
}
