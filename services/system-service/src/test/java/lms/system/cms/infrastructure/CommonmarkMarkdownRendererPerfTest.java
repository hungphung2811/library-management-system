package lms.system.cms.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lms.system.cms.infrastructure.markdown.CommonmarkMarkdownRenderer;
import org.junit.jupiter.api.Test;

/** Đo thời gian: bom '&lt;' xen kẽ phải fallback nhanh, trang thường không chậm dưới tải bom đồng thời. */
class CommonmarkMarkdownRendererPerfTest {

    private final CommonmarkMarkdownRenderer r = new CommonmarkMarkdownRenderer();

    private static String filler(int n) {
        return "lorem ipsum dolor ".repeat(n / 18 + 1).substring(0, n);
    }

    private static Map<String, String> angleBombs() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("499< + 49500 text", "<".repeat(499) + filler(49_500));
        m.put("499< + 40000 text", "<".repeat(499) + filler(40_000));
        m.put("499< + 20000 text", "<".repeat(499) + filler(20_000));
        m.put("<a  x400 + text", "<a ".repeat(400) + filler(45_000));
        m.put("<![ x400 + text", "<![".repeat(400) + filler(45_000));
        m.put("<!-- x400 + text", "<!--".repeat(400) + filler(45_000));
        m.put("<? x400 + text", "<?".repeat(400) + filler(45_000));
        m.put("![< xen ke 240", "![<".repeat(240) + filler(45_000));
        m.put("[<]( x400", "[<](".repeat(400) + filler(45_000));
        m.put("<http:// x300", "<http://x ".repeat(300) + filler(45_000));
        m.put("<a href=\" x400", "<a href=\"".repeat(400) + filler(45_000));
        m.put("&lt; xen < 499", ("<&lt;").repeat(249) + filler(45_000));
        m.put("`< xen 499", "`<".repeat(249) + filler(45_000));
        return m;
    }

    @Test
    void bomAngleXenKeTraVeDuoi500msHoacFallbackNhanh() {
        r.toHtml("warm up\n\n- a\n- b <c>"); // làm nóng JIT
        long worst = 0;
        for (Map.Entry<String, String> e : angleBombs().entrySet()) {
            long best = Long.MAX_VALUE;
            String html = null;
            for (int i = 0; i < 3; i++) {
                long t = System.nanoTime();
                html = r.toHtml(e.getValue());
                best = Math.min(best, (System.nanoTime() - t) / 1_000_000);
            }
            System.out.println("ANGLE-BOMB " + e.getKey() + " len=" + e.getValue().length() + " best=" + best
                    + " ms " + (html.startsWith("<pre>") ? "fallback" : "rendered"));
            worst = Math.max(worst, best);
            assertThat(best).as(e.getKey()).isLessThan(500);
            assertThat(html).doesNotContain("<script");
        }
        System.out.println("ANGLE-BOMB worst best-of-3 = " + worst + " ms");
    }

    @Test
    void trangThuongCoVaiTheLtVanRenderDung() {
        String html = r.toHtml("# T\n\nVì a < b và c < d, còn <span>x</span> bị escape.\n\n" + filler(20_000));
        assertThat(html).contains("<h1>T</h1>", "a &lt; b").doesNotStartWith("<pre>").doesNotContain("<span>");
    }

    @Test
    void trangThuongKhongChamKhi10BomDongThoi() throws Exception {
        r.toHtml("# warm\n\ntext");
        ExecutorService pool = Executors.newFixedThreadPool(30);
        String bomb = angleBombs().get("499< + 40000 text");
        List<Future<Long>> bombs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bombs.add(pool.submit(() -> {
                r.toHtml(bomb);
                return 0L;
            }));
        }
        List<Future<Object[]>> normal = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            normal.add(pool.submit(() -> {
                long t = System.nanoTime();
                String h = r.toHtml("# Chao\n\nnoi dung **dam** a < b\n\n" + filler(3_000));
                return new Object[] {h, (System.nanoTime() - t) / 1_000_000};
            }));
        }
        long maxNormal = 0;
        int wrong = 0;
        for (Future<Object[]> f : normal) {
            Object[] o = f.get();
            maxNormal = Math.max(maxNormal, (Long) o[1]);
            if (!((String) o[0]).contains("<h1>Chao</h1>")) {
                wrong++;
            }
        }
        for (Future<Long> f : bombs) {
            f.get();
        }
        pool.shutdownNow();
        System.out.println("CONCURRENT-10-BOMBS maxNormalMs=" + maxNormal + " wrongNormal=" + wrong);
        assertThat(wrong).isZero();
        assertThat(maxNormal).isLessThan(500);
    }
}
