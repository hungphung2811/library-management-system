package lms.tenant.export;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lms.tenant.TenantContext;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Use case xuất dữ liệu của MỘT tenant từ DB của service này thành luồng ZIP (ADR-0031 Phụ lục A). POJO: không phụ
 * thuộc Spring MVC; bean tạo trong {@code *BeanConfiguration} của service.
 *
 * <p>Hợp đồng: mỗi bảng một entry {@code <bảng>.csv} ở gốc ZIP (UTF-8 có BOM, dòng đầu là tên cột; bảng rỗng vẫn có file),
 * và {@code _meta.json} là entry CUỐI CÙNG, chỉ ghi sau khi mọi bảng đã xuất xong. Lỗi giữa chừng ném ngoại lệ ra ngoài
 * mà KHÔNG ghi {@code _meta.json} (điều phối coi là nguồn lỗi). Bảng khai {@code splitEveryRows} được chia thành
 * {@code <bảng>_001.csv}, {@code _002}, ... (mỗi file có dòng tiêu đề; bảng rỗng có {@code _001} chỉ tiêu đề); mỗi
 * file là một mục riêng trong {@code _meta.json} với {@code name} = tên file không đuôi.
 *
 * <p>Tenant: chạy trong {@code TenantContext.callAs(tenantId, ...)} nên DataSource đã bọc RLS đặt {@code app.tenant_id}
 * đúng tenant; {@code tenantId} do chỗ gọi lấy từ claim của token {@code tenant-data}, không bao giờ từ query/header.
 * Toàn bộ chạy trong một transaction chỉ đọc do {@code tx} cung cấp (nên là REPEATABLE READ cho ảnh chụp nhất quán);
 * truyền {@link TransactionOperations#withoutTransaction()} thì mỗi lô là một câu lệnh riêng.
 */
public final class TenantDataExporter {

    public static final int SCHEMA_VERSION = 1;
    public static final String META_ENTRY = "_meta.json";
    public static final int DEFAULT_BATCH = 500;

    private final String service;
    private final List<ExportTable> tables;
    private final List<ExportExclusion> excludedTables;
    private final TenantTableReader reader;
    private final TransactionOperations tx;
    private final int batchSize;

    public TenantDataExporter(String service, List<ExportTable> tables, List<ExportExclusion> excludedTables,
                              TenantTableReader reader, TransactionOperations tx, int batchSize) {
        if (service == null || !service.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("service không hợp lệ: " + service);
        }
        if (batchSize < 1) throw new IllegalArgumentException("batchSize phải >= 1");
        this.service = service;
        this.tables = List.copyOf(tables);
        this.excludedTables = List.copyOf(excludedTables);
        this.reader = reader;
        this.tx = tx;
        this.batchSize = batchSize;
        if (this.tables.stream().map(ExportTable::name).distinct().count() != this.tables.size()) {
            throw new IllegalArgumentException("trùng tên bảng xuất");
        }
    }

    public List<ExportTable> tables() {
        return tables;
    }

    /** Ghi ZIP vào {@code out} (không đóng {@code out}; chỉ {@code finish}). */
    public void export(UUID tenantId, OutputStream out) throws IOException {
        if (tenantId == null) throw new IllegalArgumentException("tenantId bắt buộc");
        try {
            TenantContext.callAs(tenantId, () -> tx.execute(status -> {
                try {
                    write(tenantId, out);
                    return null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void write(UUID tenantId, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        List<MetaTable> meta = new ArrayList<>();
        for (ExportTable table : tables) {
            FileRotator rotator = new FileRotator(zip, table, meta);
            reader.read(table, tenantId, batchSize, rotator::accept);
            rotator.finish();
        }
        zip.putNextEntry(new ZipEntry(META_ENTRY));
        zip.write(metaJson(meta).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }

    private record MetaTable(String name, String file, long rows, List<String> columns) { }

    /** Ghi các dòng của một bảng vào một hoặc nhiều entry CSV (xoay file khi đủ {@code splitEveryRows}). */
    private final class FileRotator {
        private final ZipOutputStream zip;
        private final ExportTable table;
        private final List<MetaTable> meta;
        private final boolean split;
        private OutputStreamWriter writer;
        private CsvTableWriter csv;
        private String currentName;
        private int part;
        private long rowsInFile;

        FileRotator(ZipOutputStream zip, ExportTable table, List<MetaTable> meta) {
            this.zip = zip;
            this.table = table;
            this.meta = meta;
            this.split = table.splitEveryRows() > 0;
        }

        void accept(List<String> row) throws IOException {
            if (csv != null && split && rowsInFile >= table.splitEveryRows()) closeFile();
            if (csv == null) openFile();
            csv.writeRow(row);
            rowsInFile++;
            if (rowsInFile % DEFAULT_BATCH == 0) csv.flush();
        }

        void finish() throws IOException {
            if (csv == null) openFile();
            closeFile();
        }

        private void openFile() throws IOException {
            part++;
            currentName = split ? String.format("%s_%03d", table.name(), part) : table.name();
            zip.putNextEntry(new ZipEntry(currentName + ".csv"));
            writer = new OutputStreamWriter(new NonClosing(zip), StandardCharsets.UTF_8);
            csv = new CsvTableWriter(writer);
            csv.writeRow(table.columns());
            rowsInFile = 0;
        }

        private void closeFile() throws IOException {
            csv.flush();
            zip.closeEntry();
            meta.add(new MetaTable(currentName, currentName + ".csv", rowsInFile, table.columns()));
            csv = null;
            writer = null;
        }
    }

    private String metaJson(List<MetaTable> meta) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"service\":").append(str(service)).append(",\"schemaVersion\":").append(SCHEMA_VERSION)
                .append(",\"tables\":[");
        for (int i = 0; i < meta.size(); i++) {
            MetaTable t = meta.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(str(t.name())).append(",\"file\":").append(str(t.file()))
                    .append(",\"rows\":").append(t.rows()).append(",\"columns\":[");
            for (int c = 0; c < t.columns().size(); c++) {
                if (c > 0) sb.append(',');
                sb.append(str(t.columns().get(c)));
            }
            sb.append("]}");
        }
        sb.append("],\"excluded\":[");
        List<ExportExclusion> all = new ArrayList<>(excludedTables);
        tables.forEach(t -> all.addAll(t.excludedColumns()));
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"table\":").append(str(all.get(i).target())).append(",\"reason\":")
                    .append(str(all.get(i).reason())).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String str(String s) {
        return "\"" + JdbcTenantTableReader.escapeJson(s) + "\"";
    }

    /** Ngăn đóng luồng ZIP khi đóng writer của từng entry. */
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
