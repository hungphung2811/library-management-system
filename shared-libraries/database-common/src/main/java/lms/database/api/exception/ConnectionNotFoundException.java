package lms.database.api.exception;

import java.util.Collection;

public class ConnectionNotFoundException extends DatabaseException {
    public ConnectionNotFoundException(String name, Collection<String> available) {
        super("Không tìm thấy connection \"" + name + "\". Các connection hiện có: " + available);
    }
}
