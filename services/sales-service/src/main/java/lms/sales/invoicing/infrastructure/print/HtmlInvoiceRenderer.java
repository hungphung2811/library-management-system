package lms.sales.invoicing.infrastructure.print;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import lms.sales.invoicing.application.InvoiceRendererPort;
import lms.sales.invoicing.application.InvoiceView;
import lms.sales.invoicing.domain.InvoiceNumbers;
import org.springframework.stereotype.Component;

/**
 * Bản in HTML của hóa đơn (không PDF phía server, ADR-0030): một tài liệu tĩnh, in từ trình duyệt ("In, Lưu PDF").
 * AN TOÀN: mọi giá trị từ dữ liệu (tên hàng, người mua, địa chỉ...) đi qua {@link #esc} (thoát & &lt; &gt; " ');
 * không có thẻ script, không thuộc tính sự kiện, không tải tài nguyên ngoài; CSS nằm trong thẻ style và thẻ meta CSP
 * {@code default-src 'none'} (controller cũng gửi header CSP). Không đưa dữ liệu vào thuộc tính HTML ngoài nội dung văn bản.
 * Luôn có dòng "Hóa đơn nội bộ, không thay thế hóa đơn điện tử".
 */
@Component
public class HtmlInvoiceRenderer implements InvoiceRendererPort {

    static final String CONTENT_TYPE = "text/html;charset=UTF-8";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(InvoiceNumbers.ZONE);

    @Override
    public Rendered render(InvoiceView v) {
        StringBuilder h = new StringBuilder(4096);
        h.append("<!DOCTYPE html>\n<html lang=\"vi\"><head><meta charset=\"UTF-8\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>Hóa đơn ").append(esc(v.invoiceNumber() == null ? "" : v.invoiceNumber()))
                .append("</title><style>").append(CSS).append("</style></head><body><main class=\"inv\">");
        if ("CANCELLED".equals(v.status())) {
            h.append("<p class=\"banner\">HÓA ĐƠN ĐÃ HỦY</p>");
        }
        h.append("<header><h1>").append(title(v)).append("</h1>");
        h.append("<p class=\"meta\">Mẫu số: ").append(esc(v.templateCode())).append(" &middot; Ký hiệu: ")
                .append(esc(v.series())).append(" &middot; Số: <b>").append(esc(nz(v.invoiceNumber())))
                .append("</b></p>");
        h.append("<p class=\"meta\">Ngày lập: ").append(esc(date(v.issuedAt()))).append("</p>");
        if (v.originalInvoiceNumber() != null) {
            h.append("<p class=\"meta\">Liên quan hóa đơn số: <b>").append(esc(v.originalInvoiceNumber()))
                    .append("</b></p>");
        }
        h.append("<p class=\"meta\">Đơn hàng: ").append(esc(v.orderNo())).append("</p></header>");

        h.append("<section><h2>Người bán</h2><p><b>").append(esc(v.seller().legalName())).append("</b><br>")
                .append("Mã số thuế: ").append(esc(v.seller().taxCode())).append("<br>Địa chỉ: ")
                .append(esc(v.seller().address()));
        if (v.seller().phone() != null) {
            h.append("<br>Điện thoại: ").append(esc(v.seller().phone()));
        }
        h.append("</p></section><section><h2>Người mua</h2><p>");
        var b = v.buyer();
        if (b.company() != null) {
            h.append("Đơn vị: <b>").append(esc(b.company())).append("</b><br>");
        }
        if (b.name() != null) {
            h.append(b.company() != null ? "Người mua: " : "").append(esc(b.name())).append("<br>");
        }
        if (b.taxCode() != null) {
            h.append("Mã số thuế: ").append(esc(b.taxCode())).append("<br>");
        }
        if (b.address() != null) {
            h.append("Địa chỉ: ").append(esc(b.address())).append("<br>");
        }
        if (v.paymentMethod() != null) {
            h.append("Hình thức thanh toán: ").append(esc(paymentLabel(v.paymentMethod())));
        }
        h.append("</p></section>");

        h.append("<table><thead><tr><th>STT</th><th>Tên hàng hóa, dịch vụ</th><th>ĐVT</th><th class=\"n\">Số lượng</th>")
                .append("<th class=\"n\">Đơn giá</th><th class=\"n\">Giảm giá</th><th class=\"n\">Thành tiền</th>")
                .append("<th class=\"n\">Thuế suất</th><th class=\"n\">Tiền thuế</th><th class=\"n\">Cộng</th></tr></thead><tbody>");
        for (InvoiceView.LineView l : v.lines()) {
            h.append("<tr><td>").append(l.lineNo()).append("</td><td>").append(esc(l.itemName()));
            if (l.isbn() != null) {
                h.append(" <small>(ISBN ").append(esc(l.isbn())).append(")</small>");
            }
            h.append("</td><td>").append(esc(l.unit())).append("</td><td class=\"n\">").append(l.quantity())
                    .append("</td><td class=\"n\">").append(money(l.unitPrice()))
                    .append("</td><td class=\"n\">").append(money(l.discount()))
                    .append("</td><td class=\"n\">").append(money(l.amountBeforeVat()))
                    .append("</td><td class=\"n\">").append(rate(l.vatRateBps()))
                    .append("</td><td class=\"n\">").append(money(l.vatAmount()))
                    .append("</td><td class=\"n\">").append(money(l.lineTotal())).append("</td></tr>");
        }
        h.append("</tbody></table><dl class=\"tot\"><dt>Cộng tiền hàng (chưa thuế)</dt><dd>")
                .append(money(v.totalBeforeVat())).append("</dd><dt>Tiền thuế GTGT</dt><dd>")
                .append(money(v.vatTotal())).append("</dd><dt>Tổng tiền thanh toán</dt><dd><b>")
                .append(money(v.grandTotal())).append("</b></dd></dl>");
        if (v.pricesIncludeVat()) {
            h.append("<p class=\"small\">Đơn giá đã bao gồm thuế GTGT.</p>");
        }
        if (v.amountInWords() != null) {
            h.append("<p>Số tiền viết bằng chữ: <i>").append(esc(v.amountInWords())).append("</i></p>");
        }
        if (v.cancelReason() != null) {
            h.append("<p class=\"small\">Lý do hủy: ").append(esc(v.cancelReason())).append("</p>");
        }
        h.append("<footer><p class=\"notice\">").append(esc(InvoiceView.INTERNAL_NOTICE))
                .append(".</p></footer></main></body></html>");
        return new Rendered(CONTENT_TYPE, h.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String title(InvoiceView v) {
        return switch (v.kind()) {
            case "ADJUSTMENT_DECREASE" -> "HÓA ĐƠN ĐIỀU CHỈNH GIẢM";
            case "REPLACEMENT" -> "HÓA ĐƠN THAY THẾ";
            default -> "HÓA ĐƠN GIÁ TRỊ GIA TĂNG";
        };
    }

    private static String paymentLabel(String method) {
        return switch (method) {
            case "CASH" -> "Tiền mặt";
            case "BANK_TRANSFER", "QR", "SEPAY" -> "Chuyển khoản";
            case "CARD" -> "Thẻ";
            case "COD" -> "Thu hộ khi giao hàng";
            case "MOMO" -> "Ví MoMo";
            default -> method;
        };
    }

    private static String date(Instant at) {
        return at == null ? "" : DATE_TIME.format(at);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Thuế suất theo basis point: 500 là "5%", 550 là "5,5%", null là "KCT" (không chịu thuế). */
    static String rate(Integer bps) {
        if (bps == null) {
            return "KCT";
        }
        return bps % 100 == 0 ? (bps / 100) + "%" : (bps / 100) + "," + String.format("%02d", bps % 100) + "%";
    }

    /** Số tiền đồng có dấu chấm ngăn cách hàng nghìn, không phụ thuộc Locale. */
    static String money(long amount) {
        String digits = Long.toString(amount);
        StringBuilder sb = new StringBuilder();
        int first = digits.length() % 3;
        if (first > 0) {
            sb.append(digits, 0, first);
        }
        for (int i = first; i < digits.length(); i += 3) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(digits, i, i + 3);
        }
        return sb.toString();
    }

    /** Thoát HTML cho nội dung văn bản. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String CSS = "body{font:14px/1.5 Arial,sans-serif;color:#111;margin:0;background:#fff}"
            + ".inv{max-width:900px;margin:16px auto;padding:0 16px}header{text-align:center}"
            + "h1{font-size:20px;margin:8px 0}h2{font-size:14px;margin:12px 0 4px;text-transform:uppercase}"
            + ".meta{margin:2px 0}.banner{color:#b00020;border:2px solid #b00020;text-align:center;font-weight:bold;"
            + "padding:6px}table{border-collapse:collapse;width:100%;margin:12px 0}th,td{border:1px solid #888;"
            + "padding:4px 6px;vertical-align:top}th{background:#f0f0f0}.n{text-align:right;white-space:nowrap}"
            + ".tot{display:grid;grid-template-columns:1fr auto;gap:2px 16px;max-width:420px;margin-left:auto}"
            + ".tot dt{margin:0}.tot dd{margin:0;text-align:right}.small{font-size:12px;color:#444}"
            + ".notice{text-align:center;font-style:italic;color:#444;border-top:1px solid #ccc;padding-top:8px}"
            + "@media print{.inv{margin:0}}";
}
