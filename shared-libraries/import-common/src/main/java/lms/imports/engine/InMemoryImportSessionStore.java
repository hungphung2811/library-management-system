package lms.imports.engine;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSessionMeta;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.ImportSessionStore;
import lms.imports.core.IssuedCredential;
import lms.imports.core.RowOutcome;
import lms.imports.core.SessionKey;

/**
 * Cài đặt trong bộ nhớ của {@link ImportSessionStore}, cùng ngữ nghĩa với bản Redis (TTL, giới hạn phiên, CAS bằng
 * {@code synchronized}). CHỈ dùng cho test của service con hoặc chạy cục bộ không Redis; không bao giờ là bean mặc định.
 */
public final class InMemoryImportSessionStore implements ImportSessionStore {

    private static final class Entry {
        Map<String, String> hash;
        List<ImportRow> rows;
        final List<RowOutcome> outcomes = new ArrayList<>();
        final List<IssuedCredential> creds = new ArrayList<>();
        long expiresAt;
    }

    private final Map<SessionKey, Entry> sessions = new HashMap<>();
    private final ImportLimits limits;
    private final Clock clock;

    public InMemoryImportSessionStore(ImportLimits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    private Entry live(SessionKey key) {
        Entry e = sessions.get(key);
        if (e == null) return null;
        if (e.expiresAt <= clock.millis()) {
            sessions.remove(key);
            return null;
        }
        return e;
    }

    private void touch(Entry e) {
        e.expiresAt = clock.millis() + limits.sessionTtl().toMillis();
    }

    @Override
    public synchronized void create(ImportSessionMeta meta, List<ImportRow> rows) {
        sessions.entrySet().removeIf(en -> en.getValue().expiresAt <= clock.millis());
        long open = sessions.keySet().stream()
                .filter(k -> k.tenantId().equals(meta.tenantId()) && k.userId().equals(meta.userId())).count();
        if (open >= limits.maxSessionsPerUser()) throw new ImportException(ImportErrorCode.SESSION_LIMIT);
        Entry e = new Entry();
        e.hash = new HashMap<>(meta.toHash());
        e.rows = List.copyOf(rows);
        touch(e);
        sessions.put(meta.key(), e);
    }

    @Override
    public synchronized Optional<ImportSessionMeta> findMeta(SessionKey key) {
        Entry e = live(key);
        return e == null ? Optional.empty() : Optional.of(ImportSessionMeta.fromHash(e.hash));
    }

    @Override
    public synchronized List<ImportRow> loadRows(SessionKey key) {
        Entry e = live(key);
        return e == null ? List.of() : e.rows;
    }

    @Override
    public synchronized BeginResult beginCommit(SessionKey key, String commitToken, int totalChunks) {
        Entry e = live(key);
        if (e == null) throw new ImportException(ImportErrorCode.NOT_FOUND);
        ImportSessionStatus st = ImportSessionStatus.valueOf(e.hash.get("status"));
        long hb = Long.parseLong(e.hash.getOrDefault("heartbeatAt", "0"));
        boolean stale = st == ImportSessionStatus.COMMITTING && clock.millis() - hb > limits.heartbeatStale().toMillis();
        if (st == ImportSessionStatus.VALIDATED || stale) {
            e.hash.put("status", ImportSessionStatus.COMMITTING.name());
            e.hash.put("commitToken", commitToken);
            e.hash.put("heartbeatAt", Long.toString(clock.millis()));
            e.hash.put("totalChunks", Integer.toString(totalChunks));
            return new BeginResult(true, st);
        }
        return new BeginResult(false, st);
    }

    @Override
    public synchronized void releaseCommit(SessionKey key, String commitToken) {
        Entry e = live(key);
        if (e != null && commitToken.equals(e.hash.get("commitToken"))
                && ImportSessionStatus.COMMITTING.name().equals(e.hash.get("status"))) {
            e.hash.put("status", ImportSessionStatus.VALIDATED.name());
            e.hash.put("commitToken", "");
        }
    }

    @Override
    public synchronized boolean recordProgress(SessionKey key, String commitToken, Progress p, List<RowOutcome> outcomes,
                                               List<IssuedCredential> credentials) {
        Entry e = live(key);
        if (e == null || !commitToken.equals(e.hash.get("commitToken"))) return false;
        e.hash.put("nextChunk", Integer.toString(p.nextChunk()));
        e.hash.put("processed", Integer.toString(p.processed()));
        e.hash.put("created", Integer.toString(p.created()));
        e.hash.put("skipped", Integer.toString(p.skipped()));
        e.hash.put("failed", Integer.toString(p.failed()));
        e.hash.put("heartbeatAt", Long.toString(clock.millis()));
        e.outcomes.addAll(outcomes);
        e.creds.addAll(credentials);
        touch(e);
        return true;
    }

    @Override
    public synchronized boolean finish(SessionKey key, String commitToken, ImportSessionStatus finalStatus, String errorCode) {
        Entry e = live(key);
        if (e == null || !commitToken.equals(e.hash.get("commitToken"))) return false;
        e.hash.put("status", finalStatus.name());
        e.hash.put("errorCode", errorCode == null ? "" : errorCode);
        e.hash.put("finishedAt", Long.toString(clock.millis()));
        e.hash.put("heartbeatAt", Long.toString(clock.millis()));
        touch(e);
        return true;
    }

    @Override
    public synchronized List<RowOutcome> loadOutcomes(SessionKey key) {
        Entry e = live(key);
        return e == null ? List.of() : List.copyOf(e.outcomes);
    }

    @Override
    public synchronized Optional<List<IssuedCredential>> takeCredentials(SessionKey key) {
        Entry e = live(key);
        if (e == null || e.creds.isEmpty()) return Optional.empty();
        List<IssuedCredential> out = List.copyOf(e.creds);
        e.creds.clear();
        return Optional.of(out);
    }

    @Override
    public synchronized DeleteResult delete(SessionKey key) {
        Entry e = live(key);
        if (e == null) return DeleteResult.NOT_FOUND;
        ImportSessionStatus st = ImportSessionStatus.valueOf(e.hash.get("status"));
        long hb = Long.parseLong(e.hash.getOrDefault("heartbeatAt", "0"));
        if (st == ImportSessionStatus.COMMITTING && clock.millis() - hb <= limits.heartbeatStale().toMillis()) {
            return DeleteResult.COMMITTING;
        }
        sessions.remove(key);
        return DeleteResult.DELETED;
    }
}
