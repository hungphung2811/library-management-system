package lms.migration.infrastructure.web;

import java.time.Instant;
import java.util.List;
import lms.migration.domain.MigrationScript;
import lms.migration.domain.RunResult;
import lms.migration.domain.TargetMigrationStatus;

/** Các DTO response của API migration. */
public final class MigrationResponses {

    private MigrationResponses() {
    }

    public record ScriptResponse(String version, String description, String script, String type, Integer checksum,
                          String state, Instant installedOn, String installedBy, Integer executionTimeMs) {
        public static ScriptResponse from(MigrationScript s) {
            return new ScriptResponse(s.version(), s.description(), s.script(), s.type(), s.checksum(),
                    s.state().name(), s.installedOn(), s.installedBy(), s.executionTimeMs());
        }
    }

    public record TargetStatusResponse(String target, long applied, long pending, List<ScriptResponse> scripts) {
        public static TargetStatusResponse from(TargetMigrationStatus t) {
            return new TargetStatusResponse(t.target(), t.applied(), t.pending(),
                    t.scripts().stream().map(ScriptResponse::from).toList());
        }
    }

    public record AllStatusResponse(long applied, long pending, List<TargetStatusResponse> targets) {
        public static AllStatusResponse from(List<TargetMigrationStatus> all) {
            return new AllStatusResponse(all.stream().mapToLong(TargetMigrationStatus::applied).sum(),
                    all.stream().mapToLong(TargetMigrationStatus::pending).sum(),
                    all.stream().map(TargetStatusResponse::from).toList());
        }
    }

    public record RunResultResponse(String target, int executed, String schemaVersion) {
        public static RunResultResponse from(RunResult r) {
            return new RunResultResponse(r.target(), r.executed(), r.schemaVersion());
        }
    }

    public record RunAllResponse(int executed, List<RunResultResponse> targets) {
        public static RunAllResponse from(List<RunResult> results) {
            return new RunAllResponse(results.stream().mapToInt(RunResult::executed).sum(),
                    results.stream().map(RunResultResponse::from).toList());
        }
    }
}
