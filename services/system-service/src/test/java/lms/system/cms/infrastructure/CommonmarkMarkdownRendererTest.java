package lms.system.cms.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import lms.system.cms.infrastructure.markdown.CommonmarkMarkdownRenderer;
import org.junit.jupiter.api.Test;

class CommonmarkMarkdownRendererTest {

    private final CommonmarkMarkdownRenderer renderer = new CommonmarkMarkdownRenderer();

    @Test
    void markdownThuongRaHtml() {
        String html = renderer.toHtml("# Tiêu đề\n\nĐoạn **đậm** và [liên kết](https://example.com/a).");

        assertThat(html).contains("<h1>Tiêu đề</h1>", "<strong>đậm</strong>",
                "href=\"https://example.com/a\">liên kết</a>");
    }

    @Test
    void htmlThoBiEscapeNenScriptKhongChay() {
        String html = renderer.toHtml("<script>alert(1)</script>\n\nA <img src=x onerror=alert(1)> B\n\n"
                + "<iframe src=\"https://evil\"></iframe>");

        assertThat(html).doesNotContain("<script", "<img", "<iframe");
        assertThat(html).contains("&lt;script&gt;", "&lt;img", "&lt;iframe");
    }

    @Test
    void urlJavascriptBiBoKhoiLinkVaAnh() {
        String html = renderer.toHtml("[x](javascript:alert(1)) ![y](javascript:alert(2)) [z](JaVaScRiPt:alert(3))");

        assertThat(html).doesNotContainIgnoringCase("javascript:");
    }

    @Test
    void schemeLaVaDataBiBo() {
        String html = renderer.toHtml("[a](vbscript:msgbox(1)) [b](data:text/html;base64,PHNjcmlwdD4=) "
                + "![c](data:image/svg+xml;base64,PHN2Zz4=) [d](file:///etc/passwd)");

        assertThat(html).doesNotContain("vbscript:", "data:", "file:");
    }

    @Test
    void httpHttpsMailtoVaDuongDanTuongDoiDuocGiu() {
        String html = renderer.toHtml("[a](http://x.vn) [b](https://x.vn) [c](mailto:a@x.vn) [d](/gioi-thieu)");

        assertThat(html).contains("href=\"http://x.vn\"", "href=\"https://x.vn\"", "href=\"mailto:a@x.vn\"",
                "href=\"/gioi-thieu\"");
    }

    @Test
    void rongHoacNullTraChuoiRong() {
        assertThat(renderer.toHtml("")).isEmpty();
        assertThat(renderer.toHtml(null)).isEmpty();
    }
}
