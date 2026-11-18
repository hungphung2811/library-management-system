package lms.sales.tenantdata.application;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lms.common.csv.CsvWriter;
import lms.sales.tenantdata.domain.ExcludedItem;
import lms.sales.tenantdata.domain.ExportColumn;
import lms.sales.tenantdata.domain.ExportTable;

/**
 * Xuất dữ liệu sales của một tenant thành ZIP theo luồng (ADR-0031 Phụ lục A): mỗi bảng một {@code <bảng>.csv}
 * (UTF-8 có BOM, RFC 4180, CRLF, thời điểm ISO-8601 UTC, boolean true/false, null là ô rỗng, ô văn bản bắt đầu bằng
 * {@code = + - @ TAB CR} được thêm dấu nháy đơn chống công thức) rồi {@code _meta.json} là entry cuối cùng. Đọc theo lô
 * keyset nên bộ nhớ không phụ thuộc kích thước bảng; số câu SQL = số lô, không phụ thuộc số dòng.
 *
 * <p>Lỗi giữa chừng được ném ra ngoài (ZIP cụt, không có {@code _meta.json}) để điều phối coi là lỗi nguồn. Không có
 * ảnh chụp nhất quán xuyên bảng: mỗi lô là một câu đọc riêng.
 */
public class ExportSalesTenantDataUseCase {

    private final TenantTableReader reader;
    private final List<ExportTable> tables;
    private final List<ExcludedItem> excluded;
    private final String service;
    private final int schemaVersion;
    private final int batchSize;

    public ExportSalesTenantDataUseCase(TenantTableReader reader, List<ExportTable> tables,
                                        List<ExcludedItem> excluded, String service, int schemaVersion, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize phải >= 1");
        }
        this.reader = reader;
        this.tables = List.copyOf(tables);
        this.excluded = List.copyOf(excluded);
        this.service = service;
        this.schemaVersion = schemaVersion;
        this.batchSize = batchSize;
    }

    /** Kết quả để ghi log (không chứa dữ liệu). */
    public record Summary(int tables, long rows) {
    }

    /** Ghi ZIP vào {@code target}; không đóng {@code target} (người gọi sở hữu luồng) nhưng kết thúc ZIP (finish). */
    public Summary export(UUID tenantId, OutputStream target) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8);
        List<long[]> counts = new ArrayList<>();
        long total = 0;
        for (ExportTable table : tables) {
            long rows = writeTable(tenantId, table, zip);
            counts.add(new long[] {rows});
            total += rows;
        }
        zip.putNextEntry(new ZipEntry("_meta.json"));
        zip.write(meta(counts).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        zip.flush();
        return new Summary(tables.size(), total);
    }

    private long writeTable(UUID tenantId, ExportTable table, ZipOutputStream zip) throws IOException {
        zip.putNextEntry(new ZipEntry(table.file()));
        // CsvWriter ghi BOM trong toStream; bọc để đóng writer không đóng ZIP
        CsvWriter csv = CsvWriter.toStream(new NonClosing(zip));
        List<ExportColumn> columns = table.columns();
        csv.header(columns.stream().map(ExportColumn::name).toArray(String[]::new));
        long rows = 0;
        Object afterKey = null;
        while (true) {
            List<Object[]> batch = reader.readBatch(tenantId, table, afterKey, batchSize);
            for (Object[] row : batch) {
                writeRow(csv, columns, row);
            }
            rows += batch.size();
            csv.flush();
            if (batch.size() < batchSize) {
                break;
            }
            afterKey = batch.get(batch.size() - 1)[table.keyIndex()];
        }
        csv.close();
        zip.closeEntry();
        return rows;
    }

    private static void writeRow(CsvWriter csv, List<ExportColumn> columns, Object[] row) {
        for (int i = 0; i < columns.size(); i++) {
            Object v = row[i];
            if (v == null) {
                csv.empty();
                continue;
            }
            switch (columns.get(i).kind()) {
                case NUMBER -> csv.number(((Number) v).longValue());
                case BOOLEAN -> csv.bool((Boolean) v);
                case DATE -> csv.date((LocalDate) v);
                case TIMESTAMP -> csv.text(((Instant) v).toString());
                case UUID, TEXT, JSON -> csv.text(v.toString());
            }
        }
        csv.endRow();
    }

    private String meta(List<long[]> counts) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"service\":").append(json(service)).append(",\"schemaVersion\":").append(schemaVersion)
                .append(",\"tables\":[");
        for (int i = 0; i < tables.size(); i++) {
            ExportTable t = tables.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":").append(json(t.name())).append(",\"file\":").append(json(t.file()))
                    .append(",\"rows\":").append(counts.get(i)[0]).append(",\"columns\":[");
            List<ExportColumn> cols = t.columns();
            for (int c = 0; c < cols.size(); c++) {
                if (c > 0) {
                    sb.append(',');
                }
                sb.append(json(cols.get(c).name()));
            }
            sb.append("]}");
        }
        sb.append("],\"excluded\":[");
        for (int i = 0; i < excluded.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"table\":").append(json(excluded.get(i).table())).append(",\"reason\":")
                    .append(json(excluded.get(i).reason())).append('}');
        }
        return sb.append("]}").toString();
    }

    /** Chuỗi JSON an toàn (đủ cho tên bảng/lý do do code này viết; vẫn thoát đúng mọi ký tự điều khiển). */
    static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Bỏ qua close để CsvWriter.close() chỉ xả bộ đệm, không đóng ZIP. */
    private static final class NonClosing extends FilterOutputStream {
        NonClosing(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
