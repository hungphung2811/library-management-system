package lms.sales.ordering.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Mã băm SHA-256 của NỘI DUNG đơn online (dòng hàng, hình thức nhận, phương thức thanh toán, người nhận, ghi chú), dùng
 * để phát hiện cùng {@code clientRequestId} nhưng khác nội dung. Dòng cùng đầu sách được gộp số lượng và sắp theo id nên
 * thứ tự dòng không làm đổi mã; khoảng trắng đầu/cuối của chữ bị bỏ như lúc tạo đơn. Không gồm khách và tenant (khóa đã
 * gắn với đúng khách ở bước phát lại).
 */
final class OrderRequestFingerprint {

    private OrderRequestFingerprint() {
    }

    static String of(PlaceOnlineOrderCommand cmd) {
        Map<UUID, Long> merged = new TreeMap<>();
        for (var l : cmd.lines()) {
            merged.merge(l.titleId(), (long) l.quantity(), Long::sum);
        }
        StringBuilder sb = new StringBuilder();
        merged.forEach((id, q) -> sb.append(id).append('x').append(q).append(';'));
        sb.append('|').append(cmd.fulfilment()).append('|').append(cmd.paymentMethod());
        var r = cmd.recipient();
        sb.append('|').append(r == null ? "" : trim(r.name())).append('|').append(r == null ? "" : trim(r.phone()))
                .append('|').append(r == null ? "" : trim(r.address()));
        sb.append('|').append(trim(cmd.customerNote()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
