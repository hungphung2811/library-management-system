package lms.database.api.exception;

public class ConnectionInitializationException extends DatabaseException {
    public ConnectionInitializationException(String name, Throwable cause) {
        super("Không khởi tạo được connection \"" + name + "\": " + cause.getMessage(), cause);
    }
}
