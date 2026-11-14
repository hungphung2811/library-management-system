package lms.database.api;

/**
 * Strategy tạo connection cho một loại database. Thêm loại DB mới chỉ cần khai báo thêm
 * một bean DatabaseProvider, không phải sửa manager hay business logic.
 */
public interface DatabaseProvider<C extends DatabaseConnection> {

    DatabaseType type();

    /** Tạo và kiểm tra connection; ném exception nếu không kết nối được (để manager retry). */
    C create(String name, ConnectionSpec spec);
}
