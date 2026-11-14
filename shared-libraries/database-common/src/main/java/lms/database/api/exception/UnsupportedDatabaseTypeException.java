package lms.database.api.exception;

import lms.database.api.DatabaseType;

public class UnsupportedDatabaseTypeException extends DatabaseException {
    public UnsupportedDatabaseTypeException(DatabaseType type) {
        super("Chưa có DatabaseProvider cho loại " + type + " (thiếu driver/starter trên classpath hoặc chưa đăng ký provider)");
    }
}
