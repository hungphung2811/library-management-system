package lms.system.cms.infrastructure.markdown;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lms.system.cms.application.MarkdownRendererPort;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.DefaultUrlSanitizer;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Render bằng commonmark-java (ADR-0011 mục 2.11): {@code escapeHtml(true)} biến mọi HTML thô trong Markdown thành văn
 * bản, {@code sanitizeUrls(true)} với danh sách scheme tường minh (http, https, mailto) bỏ {@code javascript:},
 * {@code data:} và scheme lạ ở cả link lẫn ảnh. Parser/renderer an toàn khi dùng chung giữa các luồng.
 *
 * <p>Phòng thủ nội dung lồng sâu/tốn CPU (commonmark đệ quy theo độ sâu khối và quét bậc hai với '&lt;'): (1) kiểm tra
 * tuyến tính độ sâu khối và số '&lt;'; (2) render trên luồng riêng (ngăn xếp lớn, hàng đợi có giới hạn) với thời hạn;
 * (3) bắt {@link StackOverflowError}. Vượt ngưỡng hoặc lỗi thì trả văn bản thô đã escape trong {@code <pre>}: endpoint
 * công khai không bao giờ 500 hay treo.
 */
public class CommonmarkMarkdownRenderer implements MarkdownRendererPort {

    static final int MAX_BLOCK_DEPTH = 32;
    static final int MAX_ANGLE_BRACKETS = 500;
    /** Ngưỡng tích (số '&lt;' x độ dài ký tự): 2 triệu ~ vài chục đến ~150 ms CPU, trang thường xa dưới mức này. */
    static final long MAX_ANGLE_WORK = 2_000_000L;
    static final long TIMEOUT_MILLIS = 2_000;
    /** Nội dung đáng ngờ chỉ được chờ ngắn hơn: bom nhanh chóng bị fallback, chừa biên cho máy tải cao. */
    static final long SUSPECT_TIMEOUT_MILLIS = 1_200;
    private static final long STACK_BYTES = 16L * 1024 * 1024;

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .urlSanitizer(new DefaultUrlSanitizer(List.of("http", "https", "mailto")))
            .build();
    /** Nội dung nhỏ và đơn giản: render ngay trên luồng gọi, không qua pool (không thể bị bom khác làm nghẽn). */
    static final int INLINE_MAX_CHARS = 4_000;
    static final int INLINE_MAX_BRACKETS = 100;
    /** Nội dung vừa: pool riêng "thường"; vượt ngưỡng này hoặc nhiều '[' là đáng ngờ, đi pool hẹp riêng. */
    static final int NORMAL_MAX_CHARS = 30_000;
    static final int NORMAL_MAX_BRACKETS = 1_000;

    private static ThreadPoolExecutor pool(int core, int max, int queue, String name) {
        ThreadPoolExecutor e = new ThreadPoolExecutor(core, max, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), runnable -> {
                    Thread t = new Thread(null, runnable, name, STACK_BYTES);
                    t.setDaemon(true);
                    return t;
                });
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    // Hai pool tách biệt: bom (đáng ngờ) chỉ chiếm pool hẹp của chính nó, không bao giờ làm trang thường bị từ chối.
    private final ExecutorService normalExecutor = pool(4, 8, 256, "cms-markdown");
    private final ExecutorService suspectExecutor = pool(2, 2, 4, "cms-markdown-suspect");

    @Override
    public String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        if (tooComplex(markdown)) {
            return fallback(markdown);
        }
        int brackets = countBrackets(markdown, NORMAL_MAX_BRACKETS + 1);
        if (markdown.length() <= INLINE_MAX_CHARS && brackets <= INLINE_MAX_BRACKETS) {
            try {
                return renderer.render(parser.parse(markdown));
            } catch (RuntimeException | StackOverflowError failure) {
                return fallback(markdown);
            }
        }
        boolean normal = markdown.length() <= NORMAL_MAX_CHARS && brackets <= NORMAL_MAX_BRACKETS;
        ExecutorService executor = normal ? normalExecutor : suspectExecutor;
        Future<String> future;
        try {
            future = executor.submit(() -> renderer.render(parser.parse(markdown)));
        } catch (RejectedExecutionException busy) {
            return fallback(markdown);
        }
        try {
            return future.get(normal ? TIMEOUT_MILLIS : SUSPECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return fallback(markdown);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return fallback(markdown);
        } catch (ExecutionException | RuntimeException | StackOverflowError failure) {
            return fallback(markdown);
        }
    }

    private static int countBrackets(String text, int cap) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '[' || c == ']') && ++n >= cap) {
                return n;
            }
        }
        return n;
    }

    /** Quét một lượt: độ sâu tiền tố khối (">", "- ", "1. ") của từng dòng và tổng số '&lt;'. */
    static boolean tooComplex(String text) {
        int angle = 0;
        int depth = 0;
        boolean prefix = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' && ++angle > MAX_ANGLE_BRACKETS) {
                return true;
            }
            if (c == '\n') {
                depth = 0;
                prefix = true;
            } else if (prefix) {
                if (c == '>') {
                    depth++;
                } else if ((c == '-' || c == '*' || c == '+') && i + 1 < text.length() && text.charAt(i + 1) == ' ') {
                    depth++;
                } else if (Character.isDigit(c)) {
                    int j = i;
                    while (j < text.length() && Character.isDigit(text.charAt(j))) {
                        j++;
                    }
                    if (j + 1 < text.length() && (text.charAt(j) == '.' || text.charAt(j) == ')')
                            && text.charAt(j + 1) == ' ') {
                        depth++;
                        i = j;
                    } else {
                        prefix = false;
                    }
                } else if (c != ' ' && c != '\t' && c != '\r') {
                    prefix = false;
                }
                if (depth > MAX_BLOCK_DEPTH) {
                    return true;
                }
            }
        }
        // Mỗi '<' chưa khép làm commonmark quét tiếp tới cuối nội dung: chi phí ~ (số '<') x (độ dài). Giới hạn theo tích
        // thay vì chỉ theo số '<' để trang dài chứa nhiều '<' bị fallback ngay, không tốn hàng giây CPU.
        return (long) angle * text.length() > MAX_ANGLE_WORK;
    }

    private static String fallback(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16).append("<pre>");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.append("</pre>\n").toString();
    }
}
