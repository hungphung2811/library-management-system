package lms.imports.core;

import java.util.ArrayList;
import java.util.List;

/**
 * SPI mà service cài ở tầng {@code application} của context sở hữu dữ liệu. Thư viện lo file, phiên, máy trạng thái và
 * chạy nền; handler chỉ khai schema, kiểm theo lô (chỉ SELECT) và ghi theo lô.
 */
public interface ImportHandler {

    ImportSchema schema();

    default String type() {
        return schema().type();
    }

    /**
     * Kiểm cả file lúc upload (chỉ đọc DB, kiểm trùng/tham chiếu THEO LÔ, không gọi lặp từng dòng). Đầu vào đã qua kiểm cú
     * pháp chung (dòng lỗi kiểu/bắt buộc đã là INVALID). Trả về đúng danh sách cùng thứ tự và số lượng, mỗi dòng có trạng
     * thái cuối (VALID/INVALID/DUPLICATE) kèm vấn đề. Trùng trong cùng file là INVALID {@code DUPLICATE_IN_FILE}.
     */
    List<ImportRow> validate(ImportContext ctx, List<ImportRow> rows);

    /**
     * Chia các dòng VALID (theo thứ tự trong phiên) thành chunk để ghi, mỗi chunk một transaction. Mặc định chia đều
     * {@code chunkSize}. Handler ghi đè khi có ràng buộc (đầu sách đi cùng bản sao, thể loại cha trước con). Phải xác định
     * (cùng đầu vào cho cùng kết quả) vì tiếp tục sau INTERRUPTED dựa vào chỉ số chunk.
     */
    default List<List<ImportRow>> planChunks(ImportContext ctx, List<ImportRow> validRows, int chunkSize) {
        List<List<ImportRow>> chunks = new ArrayList<>();
        for (int i = 0; i < validRows.size(); i += chunkSize) {
            chunks.add(validRows.subList(i, Math.min(validRows.size(), i + chunkSize)));
        }
        return chunks;
    }

    /**
     * Kiểm lại với DB hiện tại rồi ghi một chunk (handler tự mở transaction qua bean riêng, ngoài lớp này). Phải trả đúng
     * một {@link RowOutcome} cho mỗi dòng. Vi phạm unique do ghi đồng thời: rollback chunk rồi chạy lại từng dòng. Không
     * log giá trị dòng. Ném exception chỉ khi lỗi không lường trước: phiên chuyển COMMIT_FAILED.
     */
    ChunkResult commitChunk(ImportContext ctx, List<ImportRow> chunk);

    /** Gọi một lần sau chunk cuối (hoặc sau khi dừng do lỗi) để audit tóm tắt, evict cache... Lỗi ở đây chỉ được log. */
    default void onCompleted(ImportContext ctx, ImportResult result) {
    }
}
