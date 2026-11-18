package lms.identity.tenantdata.application;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Một nguồn dữ liệu của gói xuất (identity tự xuất, hoặc exporter của service khác qua HTTP, hợp đồng ở ADR-0031 phụ lục
 * A). Nguồn đẩy từng file CSV vào {@link TableSink}; lỗi nguồn ném {@link SourceFailure} (mã chung, không thông điệp
 * thô); lỗi dừng cả yêu cầu (vượt giới hạn) là {@link ExportFailure} và phải được ném lại nguyên vẹn.
 */
public interface ExportSource {

    /** Tên phần trong gói (thư mục gốc trong ZIP và khóa của manifest): identity, catalog, circulation, ... */
    String name();

    void export(UUID tenantId, UUID exportId, TableSink sink) throws SourceFailure;

    /** Nơi nhận dữ liệu của một nguồn. */
    interface TableSink {

        /** Mở file CSV (đường dẫn phẳng, ví dụ {@code authors.csv}); người gọi phải đóng luồng. */
        OutputStream openFile(String fileName) throws IOException;

        /** Thông tin một bảng (gọi sau khi đóng file tương ứng). rows không tính dòng tiêu đề. */
        void table(String name, String file, long rows, List<String> columns);

        /** Bảng/cột bị loại có chủ đích. */
        void excluded(String table, String reason);

        void schemaVersion(int version);
    }

    /** Lỗi của nguồn (có thể thử lại). {@code code} là mã chung: SOURCE_HTTP_503, SOURCE_UNREACHABLE, ... */
    class SourceFailure extends RuntimeException {

        private final String code;

        public SourceFailure(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public SourceFailure(String code, Throwable cause) {
            super(code, cause, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
