package lms.system.migration.application;

import java.util.List;
import lms.system.migration.domain.TargetMigrationStatus;

/** Đọc sổ cái migration (flyway_schema_history + script trên classpath) của các DB đích. */
public interface MigrationHistoryPort {

    /** Tên các target theo thứ tự cấu hình. */
    List<String> targets();

    /** @throws lms.system.migration.domain.UnknownTargetException target không tồn tại */
    TargetMigrationStatus status(String target);
}
