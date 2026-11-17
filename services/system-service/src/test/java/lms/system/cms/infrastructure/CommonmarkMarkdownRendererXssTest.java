package lms.system.cms.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import lms.system.cms.infrastructure.markdown.CommonmarkMarkdownRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-C3 (ADR-0011 2.11): bộ vector XSS đối kháng bổ sung cho CommonmarkMarkdownRendererTest. Đầu ra không được chứa
 * thẻ thật mang thuộc tính on*, thẻ script/svg/iframe/object/style thật, và href/src với scheme nguy hiểm.
 */
class CommonmarkMarkdownRendererXssTest {

    private final CommonmarkMarkdownRenderer renderer = new CommonmarkMarkdownRenderer();

    private static final Pattern TAG_WITH_HANDLER = Pattern.compile("<[a-zA-Z][^>]*\\son[a-zA-Z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_TAG = Pattern.compile("<(script|svg|iframe|object|embed|style|link|meta|form|base)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_URL = Pattern.compile(
            "(href|src)\\s*=\\s*\"\\s*(javascript|vbscript|data|file|blob):", Pattern.CASE_INSENSITIVE);

    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "text <img src=x onerror=alert(1)> text",
            "<svg onload=alert(1)>",
            "<iframe src=\"javascript:alert(1)\"></iframe>",
            "<a href=\"javascript:alert(1)\">x</a>",
            "<details open ontoggle=alert(1)>x</details>",
            "<style>body{background:url(javascript:alert(1))}</style>",
            "[x](javascript:alert(1))",
            "[x](JaVaScRiPt:alert(1))",
            "[x]( javascript:alert(1))",
            "[x](java&#115;cript:alert(1))",
            "[x](&#106;avascript:alert(1))",
            "[x](&#x6A;avascript:alert(1))",
            "[x](vbscript:msgbox(1))",
            "[x](data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==)",
            "[x](file:///etc/passwd)",
            "![x](javascript:alert(1))",
            "![x](data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9YWxlcnQoMSk+)",
            "<javascript:alert(1)>",
            "<data:text/html,<script>alert(1)</script>>",
            "[ref]\n\n[ref]: javascript:alert(1)",
            "[x](http://a.com \"t\" onmouseover=\"alert(1)\")",
            "[x](http://a.com \"\\\" onmouseover=\\\"alert(1)\")",
            "[x](<http://a.com\" onmouseover=\"alert(1)>)",
            "![\" onerror=\"alert(1)](http://a.com/x.png)",
            "![a](http://a.com/x.png \"\" onerror=\"alert(1)\")",
            "[x](http://a.com/%22%20onmouseover=%22alert(1))",
            "# <script>alert(1)</script>",
            "`<script>alert(1)</script>`",
            "```html\n<script>alert(1)</script>\n```",
            "<!-- --><script>alert(1)</script>",
            "<![CDATA[<script>alert(1)</script>]]>",
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            "<a\nhref=\"javascript:alert(1)\">x</a>",
            "<A HREF=\"JAVASCRIPT:alert(1)\">x</A>",
            "<img/src=x/onerror=alert(1)>",
            "<<script>alert(1);//<</script>"
    })
    void dauRaKhongCoThanHoacUrlNguyHiem(String markdown) {
        String html = renderer.toHtml(markdown);
        // Giá trị thuộc tính nằm trong "..." với dấu nháy đã escape (&quot;), nên bỏ phần trong nháy trước khi dò
        // thuộc tính on*: chỉ thuộc tính THẬT (ngoài nháy) mới là XSS.
        String outsideQuotes = html.replaceAll("\"[^\"]*\"", "\"\"");
        assertThat(outsideQuotes).as("HTML cho %s", markdown).doesNotContainPattern(TAG_WITH_HANDLER);
        assertThat(html).as("HTML cho %s", markdown).doesNotContainPattern(DANGEROUS_TAG)
                .doesNotContainPattern(DANGEROUS_URL);
    }
}
