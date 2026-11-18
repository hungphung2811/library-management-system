package lms.imports.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;

/**
 * Kiểm an toàn file trước khi đưa cho POI (ADR-0024 mục 5.1): chữ ký ZIP, từ chối OLE2 (.xls/mã hóa), macro/nhúng,
 * giới hạn số entry, kích thước giải nén ĐẾM THẬT (không tin kích thước khai báo), tỉ lệ nén, và DOCTYPE trong mọi XML.
 * Không đọc nội dung nghiệp vụ.
 */
public final class XlsxSafetyInspector {

    private static final byte[] DOCTYPE = "<!doctype".getBytes(StandardCharsets.US_ASCII);
    private static final int CONTENT_TYPES_MAX = 1 << 20;
    /** Chỉ áp tỉ lệ nén cho entry đã giải nén vượt ngưỡng này (entry nhỏ có thể nén rất tốt một cách hợp lệ). */
    private static final long RATIO_MIN_INFLATED = 1L << 20;

    private final ImportLimits limits;

    public XlsxSafetyInspector(ImportLimits limits) {
        this.limits = limits;
    }

    public void inspect(Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, ImportErrorCode.FILE_CORRUPT.defaultMessage(), List.of(), e);
        }
        if (size == 0) throw new ImportException(ImportErrorCode.FILE_REQUIRED);
        if (size > limits.maxFileBytes()) throw new ImportException(ImportErrorCode.FILE_TOO_LARGE);
        checkSignature(file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            inspectZip(zip);
        } catch (ZipException e) {
            String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (msg.contains("encrypted")) {
                throw new ImportException(ImportErrorCode.UNSUPPORTED_FILE, "File mã hóa không được hỗ trợ", List.of(), e);
            }
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, ImportErrorCode.FILE_CORRUPT.defaultMessage(), List.of(), e);
        } catch (IOException e) {
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, ImportErrorCode.FILE_CORRUPT.defaultMessage(), List.of(), e);
        }
    }

    private void checkSignature(Path file) {
        byte[] head = new byte[4];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            int n = raf.read(head);
            if (n < 4) throw new ImportException(ImportErrorCode.UNSUPPORTED_FILE);
        } catch (IOException e) {
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, ImportErrorCode.FILE_CORRUPT.defaultMessage(), List.of(), e);
        }
        if ((head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11 && (head[3] & 0xFF) == 0xE0) {
            throw new ImportException(ImportErrorCode.UNSUPPORTED_FILE, "File .xls hoặc file Excel mã hóa không được hỗ trợ");
        }
        if (head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) {
            throw new ImportException(ImportErrorCode.UNSUPPORTED_FILE);
        }
    }

    private void inspectZip(ZipFile zip) throws IOException {
        if (zip.size() > limits.maxZipEntries()) {
            throw new ImportException(ImportErrorCode.FILE_UNSAFE, "File có quá nhiều thành phần");
        }
        boolean hasContentTypes = false;
        boolean hasWorkbook = false;
        long total = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                throw new ImportException(ImportErrorCode.FILE_UNSAFE, "Đường dẫn thành phần không hợp lệ");
            }
            if (lower.endsWith("vbaproject.bin") || lower.startsWith("xl/embeddings/") || lower.startsWith("xl/activex/")
                    || lower.equals("xl/workbook.bin")) {
                throw new ImportException(ImportErrorCode.UNSUPPORTED_FILE, "File có macro, đối tượng nhúng hoặc định dạng nhị phân");
            }
            if (entry.isDirectory()) continue;
            if (entry.getSize() > limits.maxEntryBytes()) {
                throw new ImportException(ImportErrorCode.FILE_UNSAFE, "Thành phần giải nén quá lớn");
            }
            boolean xml = lower.endsWith(".xml") || lower.endsWith(".rels") || lower.endsWith(".vml");
            boolean contentTypes = name.equals("[Content_Types].xml");
            long inflated = scan(zip, entry, xml, contentTypes);
            total += inflated;
            if (total > limits.maxInflatedBytes()) {
                throw new ImportException(ImportErrorCode.FILE_UNSAFE, "Tổng dung lượng giải nén quá lớn");
            }
            long compressed = entry.getCompressedSize();
            if (compressed > 0 && inflated > RATIO_MIN_INFLATED && (double) compressed / inflated < limits.minInflateRatio()) {
                throw new ImportException(ImportErrorCode.FILE_UNSAFE, "Tỉ lệ nén bất thường");
            }
            if (contentTypes) hasContentTypes = true;
            if (name.equals("xl/workbook.xml")) hasWorkbook = true;
        }
        if (!hasContentTypes || !hasWorkbook) {
            throw new ImportException(ImportErrorCode.FILE_CORRUPT, "Không phải file Excel (.xlsx) hợp lệ");
        }
    }

    /** Giải nén thật, đếm byte, tìm DOCTYPE (bỏ qua byte NUL để bắt cả XML mã hóa UTF-16); trả số byte giải nén. */
    private long scan(ZipFile zip, ZipEntry entry, boolean xml, boolean contentTypes) throws IOException {
        long count = 0;
        int matched = 0;
        StringBuilder ct = contentTypes ? new StringBuilder() : null;
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = zip.getInputStream(entry)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                count += n;
                if (count > limits.maxEntryBytes()) {
                    throw new ImportException(ImportErrorCode.FILE_UNSAFE, "Thành phần giải nén quá lớn");
                }
                if (xml) {
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (b == 0) continue;
                        if (b >= 'A' && b <= 'Z') b = (byte) (b + 32);
                        if (b == DOCTYPE[matched]) {
                            if (++matched == DOCTYPE.length) {
                                throw new ImportException(ImportErrorCode.FILE_UNSAFE, "File chứa khai báo DOCTYPE");
                            }
                        } else {
                            matched = b == DOCTYPE[0] ? 1 : 0;
                        }
                    }
                }
                if (ct != null) {
                    if (count > CONTENT_TYPES_MAX) {
                        throw new ImportException(ImportErrorCode.FILE_UNSAFE, "[Content_Types].xml quá lớn");
                    }
                    ct.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                }
            }
        }
        if (ct != null) {
            String text = ct.toString().toLowerCase(Locale.ROOT);
            if (text.contains("macroenabled") || text.contains("ms-excel.sheet.binary") || text.contains("vbaproject")) {
                throw new ImportException(ImportErrorCode.UNSUPPORTED_FILE, "File có macro hoặc định dạng nhị phân");
            }
        }
        return count;
    }
}
