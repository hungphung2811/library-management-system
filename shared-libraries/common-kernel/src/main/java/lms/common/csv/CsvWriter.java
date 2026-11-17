package lms.common.csv;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Bộ ghi CSV dùng chung (ADR-0018 mục 2.3), thuần Java, ghi thẳng vào {@link Writer}/{@link OutputStream}.
 * <ul>
 *   <li>RFC 4180: phân tách {@code ,}, xuống dòng {@code CRLF}; ô chứa {@code ,} {@code "} CR LF thì bọc {@code "} và nhân đôi {@code "}.</li>
 *   <li>Chống CSV injection chỉ cho ô <b>văn bản</b> ({@link #text}): ô bắt đầu bằng {@code = + - @ TAB CR} được thêm {@code '} phía trước.
 *       Ô số/ngày/boolean ghi bằng phương thức kiểu riêng, không qua bộ chống (số âm giữ nguyên {@code -5}).</li>
 *   <li>{@link #toStream(OutputStream)} ghi BOM UTF-8 ({@link #BOM}) trước nội dung để Excel nhận đúng tiếng Việt có dấu.</li>
 * </ul>
 * Không an toàn khi dùng chung giữa nhiều luồng. Lỗi I/O ném {@link UncheckedIOException}.
 */
public final class CsvWriter implements Closeable, Flushable {

    /** BOM UTF-8: EF BB BF. */
    public static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Writer out;
    private boolean rowStarted;

    private CsvWriter(Writer out) {
        this.out = out;
    }

    /** Ghi BOM UTF-8 rồi mã hóa UTF-8 vào luồng (dùng cho thân phản hồi HTTP). Không đóng luồng cho tới khi {@link #close()}. */
    public static CsvWriter toStream(OutputStream stream) {
        Objects.requireNonNull(stream, "stream");
        try {
            stream.write(BOM);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new CsvWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    }

    /** Ghi vào Writer có sẵn, không thêm BOM (người gọi tự quyết định mã hóa). */
    public static CsvWriter toWriter(Writer writer) {
        return new CsvWriter(Objects.requireNonNull(writer, "writer"));
    }

    /** Ô văn bản: chống CSV injection rồi bọc nếu cần. {@code null} ghi ô rỗng. */
    public CsvWriter text(CharSequence value) {
        return cell(value == null ? "" : escape(value.toString()));
    }

    public CsvWriter number(long value) {
        return cell(Long.toString(value));
    }

    /** Số thập phân, ghi dạng không có số mũ. {@code null} ghi ô rỗng. */
    public CsvWriter number(BigDecimal value) {
        return cell(value == null ? "" : value.toPlainString());
    }

    /** {@code true}/{@code false}. {@code null} ghi ô rỗng. */
    public CsvWriter bool(Boolean value) {
        return cell(value == null ? "" : value.toString());
    }

    /** Ngày ISO {@code yyyy-MM-dd}. {@code null} ghi ô rỗng. */
    public CsvWriter date(LocalDate value) {
        return cell(value == null ? "" : value.toString());
    }

    /** Thời điểm theo múi giờ chỉ định, dạng {@code yyyy-MM-dd HH:mm:ss}. {@code null} ghi ô rỗng. */
    public CsvWriter timestamp(Instant value, ZoneId zone) {
        return cell(value == null ? "" : TIMESTAMP.format(value.atZone(zone)));
    }

    public CsvWriter empty() {
        return cell("");
    }

    /** Ghi một dòng toàn ô văn bản (thường là hàng tiêu đề) rồi kết thúc dòng. */
    public CsvWriter header(String... columns) {
        for (String column : columns) {
            text(column);
        }
        return endRow();
    }

    /** Kết thúc dòng bằng CRLF. */
    public CsvWriter endRow() {
        write("\r\n");
        rowStarted = false;
        return this;
    }

    @Override
    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CsvWriter cell(String encoded) {
        if (rowStarted) {
            write(",");
        }
        write(encoded);
        rowStarted = true;
        return this;
    }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Chống injection rồi bọc theo RFC 4180 (package-private cho test). */
    static String escape(String raw) {
        String value = raw;
        if (!value.isEmpty() && isFormulaTrigger(value.charAt(0))) {
            value = "'" + value;
        }
        if (needsQuoting(value)) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static boolean isFormulaTrigger(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    private static boolean needsQuoting(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }
}
