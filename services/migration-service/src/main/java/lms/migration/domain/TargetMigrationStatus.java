package lms.migration.domain;

import java.util.List;

/** Trạng thái migration của một DB đích. */
public record TargetMigrationStatus(String target, List<MigrationScript> scripts) {

    public TargetMigrationStatus {
        scripts = List.copyOf(scripts);
    }

    public long applied() {
        return scripts.stream().filter(s -> s.state() == MigrationState.APPLIED).count();
    }

    public long pending() {
        return scripts.stream().filter(s -> s.state() == MigrationState.PENDING).count();
    }
}
