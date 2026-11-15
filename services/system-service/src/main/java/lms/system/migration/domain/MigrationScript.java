package lms.system.migration.domain;

import java.time.Instant;

/** Một dòng trong sổ cái migration của một DB (script đã chạy hoặc đang chờ chạy). */
public record MigrationScript(
        String version,
        String description,
        String script,
        String type,
        Integer checksum,
        MigrationState state,
        Instant installedOn,
        String installedBy,
        Integer executionTimeMs) {
}
