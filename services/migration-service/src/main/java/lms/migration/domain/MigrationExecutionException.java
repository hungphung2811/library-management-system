package lms.migration.domain;

public class MigrationExecutionException extends RuntimeException {
    private final String target;

    public MigrationExecutionException(String target, String message, Throwable cause) {
        super("Migration target '" + target + "' lỗi: " + message, cause);
        this.target = target;
    }

    public String target() {
        return target;
    }
}
