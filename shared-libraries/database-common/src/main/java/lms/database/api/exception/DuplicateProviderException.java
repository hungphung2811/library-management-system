package lms.database.api.exception;

import lms.database.api.DatabaseType;

public class DuplicateProviderException extends DatabaseException {
    public DuplicateProviderException(DatabaseType type) {
        super("Có nhiều hơn một DatabaseProvider cho loại " + type);
    }
}
