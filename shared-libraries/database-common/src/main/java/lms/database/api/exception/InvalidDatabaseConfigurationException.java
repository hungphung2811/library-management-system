package lms.database.api.exception;

import java.util.List;

/** Gom toàn bộ lỗi cấu hình để người vận hành sửa một lượt. */
public class InvalidDatabaseConfigurationException extends DatabaseException {

    private final List<String> errors;

    public InvalidDatabaseConfigurationException(List<String> errors) {
        super("Cấu hình database.connections không hợp lệ:\n - " + String.join("\n - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
