package lms.imports.core;

import java.util.List;

/** Kết quả ghi một chunk: mỗi dòng của chunk phải có đúng một {@link RowOutcome}. */
public record ChunkResult(List<RowOutcome> outcomes, List<IssuedCredential> credentials) {

    public ChunkResult {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }

    public static ChunkResult of(List<RowOutcome> outcomes) {
        return new ChunkResult(outcomes, List.of());
    }

    public int count(OutcomeStatus status) {
        int n = 0;
        for (RowOutcome o : outcomes) if (o.status() == status) n++;
        return n;
    }
}
