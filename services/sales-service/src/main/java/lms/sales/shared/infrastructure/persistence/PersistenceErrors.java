package lms.sales.shared.infrastructure.persistence;

import java.sql.SQLException;
import java.util.Locale;

/** Nhận diện lỗi ràng buộc DB từ chuỗi nguyên nhân của exception JPA/Hibernate (không phụ thuộc exception đã dịch). */
public final class PersistenceErrors {

    private static final String UNIQUE_VIOLATION = "23505";

    private PersistenceErrors() {
    }

    /** Vi phạm UNIQUE mà thông điệp có chứa một trong các gợi ý (tên ràng buộc); không có gợi ý thì mọi UNIQUE. */
    public static boolean isUniqueViolation(Throwable error, String... constraintHints) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return matches(sql.getMessage(), constraintHints);
            }
        }
        return false;
    }

    /** Xung đột khóa lạc quan (version lệch) ở bất kỳ tầng nào của chuỗi nguyên nhân. */
    public static boolean isOptimisticLock(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String name = t.getClass().getName();
            if (name.equals("jakarta.persistence.OptimisticLockException")
                    || name.equals("org.hibernate.StaleObjectStateException")
                    || name.equals("org.hibernate.StaleStateException")
                    || name.equals("org.springframework.orm.ObjectOptimisticLockingFailureException")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String message, String[] hints) {
        if (hints == null || hints.length == 0) {
            return true;
        }
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        for (String h : hints) {
            if (m.contains(h.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
