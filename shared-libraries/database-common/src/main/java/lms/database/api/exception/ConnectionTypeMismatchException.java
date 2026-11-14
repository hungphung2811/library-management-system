package lms.database.api.exception;

public class ConnectionTypeMismatchException extends DatabaseException {
    public ConnectionTypeMismatchException(String name, Class<?> expected, Class<?> actual) {
        super("Connection \"" + name + "\" là " + actual.getSimpleName() + ", không phải " + expected.getSimpleName());
    }
}
