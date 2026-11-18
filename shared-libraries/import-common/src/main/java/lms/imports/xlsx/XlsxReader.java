package lms.imports.xlsx;

import java.util.List;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSchema;

/** Đọc file .xlsx của người dùng thành các dòng thô. POI chỉ xuất hiện sau interface này (có thể đổi thư viện sau). */
public interface XlsxReader {

    /**
     * Kiểm an toàn rồi đọc streaming. Ném {@code ImportException} (UNSUPPORTED_FILE, FILE_UNSAFE, FILE_CORRUPT,
     * TEMPLATE_MISMATCH, EMPTY, TOO_MANY_ROWS...). Dòng trống bị bỏ qua; ô quá dài thành lỗi dòng CELL_TOO_LONG.
     */
    List<ImportRow> read(byte[] data, ImportSchema schema);
}
