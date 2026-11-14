package lms.migration.application;

import java.util.List;
import lms.migration.domain.TargetMigrationStatus;

public class GetMigrationStatusUseCase {

    private final MigrationHistoryPort history;

    public GetMigrationStatusUseCase(MigrationHistoryPort history) {
        this.history = history;
    }

    public List<TargetMigrationStatus> all() {
        return history.targets().stream().map(history::status).toList();
    }

    public TargetMigrationStatus byTarget(String target) {
        return history.status(target);
    }
}
