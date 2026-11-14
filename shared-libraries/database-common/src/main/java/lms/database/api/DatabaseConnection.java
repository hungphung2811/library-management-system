package lms.database.api;

/**
 * Một kết nối có tên tới một database. Interface này chỉ lo vòng đời và quan sát;
 * cách truy cập dữ liệu nằm ở các interface con theo từng loại DB.
 */
public interface DatabaseConnection extends AutoCloseable {

    String name();

    DatabaseType type();

    /** Kiểm tra thật tới database, không ném exception: lỗi được trả về dưới dạng DOWN. */
    HealthStatus health();

    /** Giải phóng pool/client. Phải idempotent. */
    @Override
    void close();
}
