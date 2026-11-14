package lms.database.api.exception;

public class ConnectionDisabledException extends DatabaseException {
    public ConnectionDisabledException(String name) {
        super("Connection \"" + name + "\" đang bị tắt (enabled=false)");
    }
}
