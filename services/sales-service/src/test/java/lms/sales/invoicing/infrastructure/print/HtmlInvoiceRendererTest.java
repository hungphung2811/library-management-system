package lms.sales.invoicing.infrastructure.print;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lms.sales.invoicing.application.InvoiceView;
import lms.sales.invoicing.domain.BuyerInfo;
import lms.sales.invoicing.domain.SellerInfo;
import org.junit.jupiter.api.Test;

/** Bản in HTML: thoát HTML mọi dữ liệu (chống XSS), không script, không thuộc tính sự kiện, có cảnh báo hóa đơn nội bộ. */
class HtmlInvoiceRendererTest {

    private static final String XSS = "<script>alert(1)</script>\"><img src=x onerror=alert(2)>'&amp;";
    private static final Set<String> ALLOWED_TAGS = Set.of("html", "head", "meta", "title", "style", "body", "main",
            "header", "h1", "h2", "p", "section", "table", "thead", "tbody", "tr", "th", "td", "small", "b", "i", "br",
            "dl", "dt", "dd", "footer");
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z!][a-zA-Z0-9]*)([^>]*)>");

    private static InvoiceView view(String status, String kind) {
        return new InvoiceView(UUID.randomUUID(), kind, status, UUID.randomUUID(), XSS, null, null, XSS, XSS, XSS, 2026,
                12L, "00000012", Instant.parse("2026-11-19T03:00:00Z"), null, null, null, status.equals("CANCELLED") ? XSS : null,
                new SellerInfo(XSS, "0123456789", XSS, XSS, XSS), new BuyerInfo(XSS, XSS, "0123456789", XSS, XSS),
                "VND", true, "CASH", 100_000, 5_000, 105_000, "Một trăm linh năm nghìn đồng", "INTERNAL", null,
                Instant.now(), 0, List.of(new InvoiceView.LineView(1, XSS, XSS, XSS, 1, 105_000, 0, 100_000, 500, 5_000,
                105_000)), List.of(), InvoiceView.INTERNAL_NOTICE);
    }

    private static String html(InvoiceView v) {
        var r = new HtmlInvoiceRenderer().render(v);
        assertThat(r.contentType()).isEqualTo("text/html;charset=UTF-8");
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void duLieuDocHaiDuocThoatKhongSinhThePhanTuNguyHiem() {
        for (String status : List.of("ISSUED", "CANCELLED")) {
            String out = html(view(status, "ORIGINAL"));
            assertThat(out).doesNotContain("<script").doesNotContain("<img").doesNotContain("</script");
            assertThat(out).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
            assertThat(out).contains("&quot;&gt;&lt;img src=x onerror=alert(2)&gt;&#39;&amp;amp;");
            // mọi thẻ trong tài liệu đều thuộc danh sách trắng và không có thuộc tính sự kiện/URL
            Matcher m = TAG.matcher(out);
            int tags = 0;
            while (m.find()) {
                tags++;
                String name = m.group(2).toLowerCase();
                if (!name.equals("!doctype")) {
                    assertThat(ALLOWED_TAGS).as("thẻ <" + name + ">").contains(name);
                }
                assertThat(m.group(3).toLowerCase()).as("thuộc tính của <" + name + ">")
                        .doesNotContainPattern("\\son[a-z]+\\s*=").doesNotContain("javascript:")
                        .doesNotContain("href").doesNotContain("src=");
            }
            assertThat(tags).isGreaterThan(20);
        }
    }

    @Test
    void khongCoThuTaiTaiNguyenNgoaiVaCoCspTrongTaiLieu() {
        String out = html(view("ISSUED", "ORIGINAL"));
        assertThat(out).contains("Content-Security-Policy").contains("default-src 'none'");
        assertThat(out).doesNotContain("<link").doesNotContain("<iframe").doesNotContain("<object")
                .doesNotContain("<form").doesNotContain("http://").doesNotContain("https://");
    }

    @Test
    void banInCoCanhBaoNoiBoVaTieuDeTheoLoai() {
        assertThat(html(view("ISSUED", "ORIGINAL"))).contains("Hóa đơn nội bộ, không thay thế hóa đơn điện tử")
                .contains("HÓA ĐƠN GIÁ TRỊ GIA TĂNG").doesNotContain("HÓA ĐƠN ĐÃ HỦY");
        assertThat(html(view("ISSUED", "REPLACEMENT"))).contains("HÓA ĐƠN THAY THẾ");
        assertThat(html(view("ISSUED", "ADJUSTMENT_DECREASE"))).contains("HÓA ĐƠN ĐIỀU CHỈNH GIẢM");
        assertThat(html(view("CANCELLED", "ORIGINAL"))).contains("HÓA ĐƠN ĐÃ HỦY");
    }

    @Test
    void dinhDangTienThueSuatVaThoatKyTu() {
        assertThat(HtmlInvoiceRenderer.money(0)).isEqualTo("0");
        assertThat(HtmlInvoiceRenderer.money(999)).isEqualTo("999");
        assertThat(HtmlInvoiceRenderer.money(1_000)).isEqualTo("1.000");
        assertThat(HtmlInvoiceRenderer.money(2_500_000)).isEqualTo("2.500.000");
        assertThat(HtmlInvoiceRenderer.money(1_234_567_890)).isEqualTo("1.234.567.890");
        assertThat(HtmlInvoiceRenderer.rate(500)).isEqualTo("5%");
        assertThat(HtmlInvoiceRenderer.rate(550)).isEqualTo("5,50%");
        assertThat(HtmlInvoiceRenderer.rate(0)).isEqualTo("0%");
        assertThat(HtmlInvoiceRenderer.rate(null)).isEqualTo("KCT");
        assertThat(HtmlInvoiceRenderer.esc("a&b<c>d\"e'f")).isEqualTo("a&amp;b&lt;c&gt;d&quot;e&#39;f");
        assertThat(HtmlInvoiceRenderer.esc(null)).isEmpty();
    }
}
