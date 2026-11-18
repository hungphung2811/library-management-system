package lms.identity.tenantdata.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Nơi lưu gói xuất và file tạm (ADR-0031 D3). Mọi byte ghi qua cổng này PHẢI được mã hóa lúc lưu và giải mã theo luồng khi
 * đọc (không đọc cả file vào bộ nhớ). Khóa lưu chỉ gồm {@code [A-Za-z0-9._-]}, do hệ thống sinh (không từ client).
 * Phát hiện sửa/cắt file: {@link #open} trả luồng ném {@link IOException} khi đọc tới phần bị đổi.
 */
public interface ExportStoragePort {

    /** false khi chưa cấu hình khóa mã hóa: tính năng tắt (API trả 503 EXPORT_NOT_CONFIGURED). */
    boolean configured();

    OutputStream create(String key) throws IOException;

    InputStream open(String key) throws IOException;

    boolean exists(String key);

    /** Xóa nếu có (không lỗi khi không tồn tại). */
    void delete(String key);

    /** Khóa các file có tiền tố này (dọn file tạm của một yêu cầu). */
    void deleteByPrefix(String prefix);
}
