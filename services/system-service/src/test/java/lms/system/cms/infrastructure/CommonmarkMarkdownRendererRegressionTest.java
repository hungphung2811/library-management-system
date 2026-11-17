package lms.system.cms.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lms.system.cms.infrastructure.markdown.CommonmarkMarkdownRenderer;
import org.junit.jupiter.api.Test;

/** Hồi quy BUG-3 vòng 2 (tester): biến thể bom mới, bom đồng thời và đo thời gian thật. */
class CommonmarkMarkdownRendererRegressionTest {

    private final CommonmarkMarkdownRenderer r = new CommonmarkMarkdownRenderer();

    private static String rep(String s, int n) {
        return s.repeat(n);
    }

    private static List<String> bombs() {
        StringBuilder nestedHeadings = new StringBuilder();
        for (int i = 0; i < 20000; i++) nestedHeadings.append("> # h\n");
        StringBuilder alt = new StringBuilder();
        for (int i = 0; i < 12000; i++) alt.append(i % 2 == 0 ? "> " : "- ");
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < 60000; i++) refs.append("[r").append(i).append("]: http://x/").append(i).append("\n");
        StringBuilder refUse = new StringBuilder();
        for (int i = 0; i < 30000; i++) refUse.append("[a][r").append(i).append("] ");
        return List.of(rep(">", 49000) + "x", rep("- ", 24000) + "x", rep("<", 49000), nestedHeadings.toString(),
                alt + "x", refs.toString(), refUse.toString(), rep("1. ", 20000) + "x", rep("*", 49000),
                rep("[", 49000), rep("[a](", 20000), rep("`", 49000), rep("![", 25000), rep("\t", 40000) + "- x",
                rep("  ", 20000) + "> x", rep("> - ", 12000) + "x", rep("<a ", 20000), rep("&", 49000),
                rep("_a", 30000), rep("\\", 49000));
    }

    @Test
    void moiBomTraVeNhanhVaKhongLoHtmlTho() {
        int i = 0;
        for (String bomb : bombs()) {
            long t = System.nanoTime();
            String html = r.toHtml(bomb);
            long ms = (System.nanoTime() - t) / 1_000_000;
            System.out.println("BOMB#" + (i++) + " len=" + bomb.length() + " -> " + ms + " ms"
                    + (html.startsWith("<pre>") ? " fallback" : " rendered"));
            assertThat(html).isNotNull();
            assertThat(ms).as("bom %d", i).isLessThan(2_500);
            if (html.startsWith("<pre>") && !html.startsWith("<pre><code")) {
                String inner = html.substring(5, html.length() - "</pre>\n".length());
                assertThat(inner).doesNotContain("<").doesNotContain(">").doesNotContain("\"");
            }
        }
    }

    @Test
    void noiDungTrongNguongVanRenderDungSauBom() {
        r.toHtml(rep(">", 49000) + "x");
        assertThat(r.toHtml("# Tieu de\n\n- a\n- b\n")).contains("<h1>Tieu de</h1>").contains("<li>a</li>");
        assertThat(r.toHtml("> ".repeat(30) + "deep")).contains("deep").doesNotStartWith("<pre>");
        assertThat(r.toHtml("<script>alert(1)</script> [x](javascript:alert(1))")).doesNotContain("<script>")
                .doesNotContain("href=\"javascript");
        assertThat(r.toHtml("> ".repeat(32) + "ok")).doesNotStartWith("<pre>");
        assertThat(r.toHtml("> ".repeat(33) + "ok")).startsWith("<pre>");
        assertThat(r.toHtml("a < b ".repeat(500))).doesNotStartWith("<pre>");
        assertThat(r.toHtml("a < b ".repeat(501))).startsWith("<pre>");
    }

    @Test
    void taiDongThoiBomKhongLamTrangThuongSaiHoacCham() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(40);
        String bomb = rep(">", 49000) + "x";
        String slowBomb = rep("[a](", 20000);
        List<Future<Long>> bombFutures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String b = i % 2 == 0 ? bomb : slowBomb;
            bombFutures.add(pool.submit(() -> {
                long t = System.nanoTime();
                r.toHtml(b);
                return (System.nanoTime() - t) / 1_000_000;
            }));
        }
        List<Future<Object[]>> normal = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            normal.add(pool.submit(() -> {
                long t = System.nanoTime();
                String h = r.toHtml("# Chao\n\nnoi dung **dam**");
                return new Object[] {h, (System.nanoTime() - t) / 1_000_000};
            }));
        }
        long maxBomb = 0;
        long maxNormal = 0;
        int wrongNormal = 0;
        for (Future<Long> f : bombFutures) maxBomb = Math.max(maxBomb, f.get());
        for (Future<Object[]> f : normal) {
            Object[] o = f.get();
            maxNormal = Math.max(maxNormal, (Long) o[1]);
            if (!((String) o[0]).contains("<h1>Chao</h1>")) wrongNormal++;
        }
        pool.shutdownNow();
        System.out.println("CONCURRENT maxBombMs=" + maxBomb + " maxNormalMs=" + maxNormal + " wrongNormal=" + wrongNormal);
        assertThat(wrongNormal).as("trang thường bị fallback sai khi có bom đồng thời").isZero();
        assertThat(maxNormal).isLessThan(2_500);
    }
}
