package lms.system.migration.domain;

/** Kết quả một lần chạy migration lên một DB đích. */
public record RunResult(String target, int executed, String schemaVersion) {
}
