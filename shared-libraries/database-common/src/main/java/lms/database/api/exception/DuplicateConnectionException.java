package lms.database.api.exception;

public class DuplicateConnectionException extends DatabaseException {
    public DuplicateConnectionException(String name) {
        super("Connection \"" + name + "\" đã được đăng ký");
    }
}
