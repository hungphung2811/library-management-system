package lms.system.cms.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lms.system.cms.infrastructure.markdown.CommonmarkMarkdownRenderer;
import org.junit.jupiter.api.Test;

/** BUG-3: Markdown lồng sâu/tốn CPU không làm renderer ném lỗi hay treo. */
class CommonmarkMarkdownRendererBombTest {

    private final CommonmarkMarkdownRenderer renderer = new CommonmarkMarkdownRenderer();

    @Test
    void noiDungLongSauHoacTonCpuTraVanBanEscapeKhongNemLoi() {
        for (String bomb : List.of(">".repeat(49_000), "- ".repeat(24_000) + "x", "<".repeat(49_000),
                "1. ".repeat(10_000) + "x", "> - ".repeat(10_000))) {
            long t0 = System.nanoTime();
            String html = renderer.toHtml(bomb);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            assertThat(ms).as("thời gian render").isLessThan(3_000);
            assertThat(html).startsWith("<pre>").doesNotContain("<script");
            assertThat(html).doesNotContain("<<", ">>");
        }
    }

    @Test
    void noiDungBinhThuongVanRenderDayDu() {
        String html = renderer.toHtml("> trích dẫn\n\n- a\n  - b\n\n1. một\n\n**đậm** 1 < 2");

        assertThat(html).contains("<blockquote>", "<ul>", "<ol>", "<strong>đậm</strong>").doesNotContain("<pre>");
    }

    @Test
    void doSauTrongNguongVanRender() {
        assertThat(renderer.toHtml("> ".repeat(30) + "x")).contains("<blockquote>").doesNotContain("<pre>");
    }
}
