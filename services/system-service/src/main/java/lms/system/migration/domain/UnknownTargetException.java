package lms.system.migration.domain;

public class UnknownTargetException extends RuntimeException {
    public UnknownTargetException(String target) {
        super("Không có target migration: " + target);
    }
}
