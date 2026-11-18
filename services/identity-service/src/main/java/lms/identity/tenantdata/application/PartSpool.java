package lms.identity.tenantdata.application;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lms.identity.tenantdata.application.ExportSource.SourceFailure;
import lms.identity.tenantdata.application.ExportSource.TableSink;
import lms.identity.tenantdata.domain.ExportedTable;

/**
 * Nhận dữ liệu của MỘT nguồn và ghi vào file tạm đã mã hóa (một ZIP nhẹ), tính SHA-256 từng CSV khi ghi. Mỗi lần thử một
 * nguồn dùng một spool riêng nên thử lại không làm trùng entry trong gói tổng; gói tổng chỉ chép spool của nguồn đã
 * xong. Lỗi ghi lưu trữ (đĩa đầy...) thành {@link ExportFailure} STORAGE_ERROR để adapter nguồn không nhầm với lỗi nguồn.
 */
final class PartSpool implements TableSink, AutoCloseable {

    private static final Pattern FILE_NAME = Pattern.compile("[a-z0-9_]{1,100}\\.csv");

    record Excluded(String table, String reason) {
    }

    record Result(int schemaVersion, List<ExportedTable> tables, List<Excluded> excluded) {
    }

    private record FileInfo(long bytes, String sha256) {
    }

    private record TableInfo(String name, String file, long rows, List<String> columns) {
    }

    private final ExportStoragePort storage;
    private final String key;
    private final SizeBudget budget;
    private ZipOutputStream zip;
    private final Map<String, FileInfo> files = new LinkedHashMap<>();
    private final List<TableInfo> tables = new ArrayList<>();
    private final List<Excluded> excluded = new ArrayList<>();
    private int schemaVersion = 1;
    private long bytesCharged;

    PartSpool(ExportStoragePort storage, String key, SizeBudget budget) {
        this.storage = storage;
        this.key = key;
        this.budget = budget;
    }

    @Override
    public OutputStream openFile(String fileName) throws IOException {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches() || files.containsKey(fileName)) {
            throw new SourceFailure("SOURCE_INVALID");
        }
        try {
            if (zip == null) {
                zip = new ZipOutputStream(storage.create(key));
                zip.setLevel(Deflater.BEST_SPEED);
            }
            zip.putNextEntry(new ZipEntry(fileName));
        } catch (IOException e) {
            throw new ExportFailure("STORAGE_ERROR");
        }
        files.put(fileName, null);
        return new EntryStream(fileName);
    }

    @Override
    public void table(String name, String file, long rows, List<String> columns) {
        tables.add(new TableInfo(name, file, rows, columns == null ? List.of() : List.copyOf(columns)));
    }

    @Override
    public void excluded(String table, String reason) {
        excluded.add(new Excluded(table, reason));
    }

    @Override
    public void schemaVersion(int version) {
        this.schemaVersion = version;
    }

    /** Đóng spool và đối chiếu: mỗi CSV có thông tin bảng và ngược lại; trả danh sách bảng đã xuất. */
    Result finish() {
        try {
            if (zip != null) {
                zip.close();
            }
        } catch (IOException e) {
            throw new ExportFailure("STORAGE_ERROR");
        }
        List<ExportedTable> out = new ArrayList<>();
        for (TableInfo t : tables) {
            FileInfo info = files.get(t.file());
            if (info == null || t.rows() < 0) {
                throw new SourceFailure("SOURCE_INVALID");
            }
            out.add(new ExportedTable(t.name(), t.file(), t.rows(), info.bytes(), info.sha256(), t.columns()));
        }
        for (Map.Entry<String, FileInfo> f : files.entrySet()) {
            if (f.getValue() == null || tables.stream().noneMatch(t -> t.file().equals(f.getKey()))) {
                throw new SourceFailure("SOURCE_INVALID");
            }
        }
        return new Result(schemaVersion, out, List.copyOf(excluded));
    }

    long bytesCharged() {
        return bytesCharged;
    }

    /** Hủy lần thử: đóng, xóa file tạm, trả ngân sách. */
    void abort() {
        try {
            if (zip != null) {
                zip.close();
            }
        } catch (IOException ignored) {
            // đang hủy
        }
        storage.delete(key);
        budget.release(bytesCharged);
        bytesCharged = 0;
    }

    @Override
    public void close() {
        // spool giữ file tạm tới khi người điều phối dọn theo tiền tố
    }

    private final class EntryStream extends OutputStream {

        private final String fileName;
        private final MessageDigest digest;
        private long count;
        private boolean closed;

        EntryStream(String fileName) {
            this.fileName = fileName;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            budget.add(len);
            bytesCharged += len;
            digest.update(b, off, len);
            count += len;
            try {
                zip.write(b, off, len);
            } catch (IOException e) {
                throw new ExportFailure("STORAGE_ERROR");
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                zip.closeEntry();
            } catch (IOException e) {
                throw new ExportFailure("STORAGE_ERROR");
            }
            files.put(fileName, new FileInfo(count, HexFormat.of().formatHex(digest.digest())));
        }
    }
}
