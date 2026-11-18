package lms.imports.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;

/**
 * Phiên preview trong Redis (ADR-0024 mục 2.4/3.5), dùng thẳng {@link RedisConnectionFactory}, độc lập với
 * {@code lms.cache.enabled}. FAIL-CLOSED: mọi lỗi Redis (kể cả không có factory) thành {@code STORAGE_UNAVAILABLE}.
 *
 * <pre>
 * lms:import:v1:t:{tenant}:u:{user}:{importId}:meta    HASH
 * lms:import:v1:t:{tenant}:u:{user}:{importId}:rows    STRING gzip(JSON dòng)
 * lms:import:v1:t:{tenant}:u:{user}:{importId}:result  LIST   mỗi phần tử gzip(JSON kết quả một chunk)
 * lms:import:v1:t:{tenant}:u:{user}:{importId}:creds   LIST   "login\0secret", lấy một lần bằng script LRANGE+DEL
 * lms:import:v1:t:{tenant}:u:{user}:active             ZSET   importId -> hạn (giới hạn số phiên mở)
 * </pre>
 *
 * Mọi chuyển trạng thái đi qua script Lua nên nguyên tử (CAS): bấm confirm hai lần chỉ một lần giành được phiên.
 */
public final class RedisImportSessionStore implements ImportSessionStore {

    private static final Logger log = LoggerFactory.getLogger(RedisImportSessionStore.class);

    private static final String CREATE = """
            redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', ARGV[2])
            if redis.call('ZCARD', KEYS[5]) >= tonumber(ARGV[3]) then return 0 end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
            redis.call('HSET', KEYS[1], unpack(ARGV, 6))
            redis.call('SET', KEYS[2], ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            redis.call('PEXPIRE', KEYS[2], ARGV[1])
            redis.call('ZADD', KEYS[5], tonumber(ARGV[2]) + tonumber(ARGV[1]), ARGV[4])
            redis.call('PEXPIRE', KEYS[5], ARGV[1])
            return 1
            """;

    private static final String BEGIN = """
            local st = redis.call('HGET', KEYS[1], 'status')
            if not st then return {2, ''} end
            local hb = tonumber(redis.call('HGET', KEYS[1], 'heartbeatAt') or '0')
            if st == 'VALIDATED' or (st == 'COMMITTING' and (tonumber(ARGV[2]) - hb) > tonumber(ARGV[3])) then
              redis.call('HSET', KEYS[1], 'status', 'COMMITTING', 'commitToken', ARGV[1], 'heartbeatAt', ARGV[2], 'totalChunks', ARGV[4])
              return {1, st}
            end
            return {0, st}
            """;

    private static final String RELEASE = """
            if redis.call('HGET', KEYS[1], 'commitToken') == ARGV[1] and redis.call('HGET', KEYS[1], 'status') == 'COMMITTING' then
              redis.call('HSET', KEYS[1], 'status', 'VALIDATED', 'commitToken', '')
              return 1
            end
            return 0
            """;

    private static final String UPDATE = """
            if redis.call('HGET', KEYS[1], 'commitToken') ~= ARGV[1] then return 0 end
            local n = tonumber(ARGV[6])
            local first = 7 + n
            if #ARGV >= first then redis.call('HSET', KEYS[1], unpack(ARGV, first)) end
            redis.call('HSET', KEYS[1], 'heartbeatAt', ARGV[3])
            if ARGV[5] ~= '' then redis.call('RPUSH', KEYS[3], ARGV[5]) end
            if n > 0 then redis.call('RPUSH', KEYS[4], unpack(ARGV, 7, 6 + n)) end
            for i = 1, 4 do redis.call('PEXPIRE', KEYS[i], ARGV[2]) end
            redis.call('ZADD', KEYS[5], tonumber(ARGV[3]) + tonumber(ARGV[2]), ARGV[4])
            redis.call('PEXPIRE', KEYS[5], ARGV[2])
            return 1
            """;

    private static final String DELETE = """
            local st = redis.call('HGET', KEYS[1], 'status')
            if not st then
              redis.call('ZREM', KEYS[5], ARGV[1])
              redis.call('DEL', KEYS[2], KEYS[3], KEYS[4])
              return 0
            end
            if st == 'COMMITTING' then
              local hb = tonumber(redis.call('HGET', KEYS[1], 'heartbeatAt') or '0')
              if (tonumber(ARGV[2]) - hb) <= tonumber(ARGV[3]) then return 2 end
            end
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
            redis.call('ZREM', KEYS[5], ARGV[1])
            return 1
            """;

    private static final String TAKE = """
            local v = redis.call('LRANGE', KEYS[1], 0, -1)
            redis.call('DEL', KEYS[1])
            return v
            """;

    private static final TypeReference<List<ImportRow>> ROWS = new TypeReference<>() { };
    private static final TypeReference<List<RowOutcome>> OUTCOMES = new TypeReference<>() { };

    private final RedisConnectionFactory factory;
    private final ObjectMapper mapper;
    private final ImportLimits limits;
    private final Clock clock;

    /** {@code factory} có thể null (service không có Redis): mọi thao tác ném STORAGE_UNAVAILABLE. */
    public RedisImportSessionStore(RedisConnectionFactory factory, ObjectMapper mapper, ImportLimits limits, Clock clock) {
        this.factory = factory;
        this.mapper = (mapper == null ? new ObjectMapper() : mapper.copy())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.limits = limits;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ khóa

    static String base(SessionKey k) {
        return "lms:import:v1:t:" + k.tenantId() + ":u:" + safe(k.userId()) + ":" + k.importId() + ":";
    }

    static String activeKey(SessionKey k) {
        return "lms:import:v1:t:" + k.tenantId() + ":u:" + safe(k.userId()) + ":active";
    }

    private static String safe(String userId) {
        return userId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[][] keys(SessionKey k) {
        String base = base(k);
        return new byte[][] {b(base + "meta"), b(base + "rows"), b(base + "result"), b(base + "creds"), b(activeKey(k))};
    }

    // ------------------------------------------------------------------ thực thi, fail-closed

    private <T> T exec(Function<RedisConnection, T> action) {
        if (factory == null) {
            throw unavailable(null);
        }
        try (RedisConnection connection = factory.getConnection()) {
            return action.apply(connection);
        } catch (ImportException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(e);
        }
    }

    private static ImportException unavailable(Throwable cause) {
        if (cause != null) {
            log.warn("Redis không dùng được cho phiên nhập: {}", cause.getClass().getSimpleName());
        }
        return new ImportException(ImportErrorCode.STORAGE_UNAVAILABLE, ImportErrorCode.STORAGE_UNAVAILABLE.defaultMessage(),
                List.of(), cause);
    }

    private static <T> T eval(RedisConnection c, String script, ReturnType type, byte[][] keys, List<byte[]> args) {
        byte[][] all = new byte[keys.length + args.size()][];
        System.arraycopy(keys, 0, all, 0, keys.length);
        for (int i = 0; i < args.size(); i++) all[keys.length + i] = args.get(i);
        return c.scriptingCommands().eval(b(script), type, keys.length, all);
    }

    // ------------------------------------------------------------------ ImportSessionStore

    @Override
    public void create(ImportSessionMeta meta, List<ImportRow> rows) {
        byte[] blob = gzip(toJson(rows));
        if (blob.length > limits.maxRowsBlobBytes()) {
            throw new ImportException(ImportErrorCode.FILE_UNSAFE, "Dữ liệu file quá lớn để lưu phiên xem trước");
        }
        Map<String, String> hash = meta.toHash();
        hash.put("rowsBytes", Integer.toString(blob.length));
        long now = clock.millis();
        List<byte[]> args = new ArrayList<>();
        args.add(b(Long.toString(limits.sessionTtl().toMillis())));
        args.add(b(Long.toString(now)));
        args.add(b(Integer.toString(limits.maxSessionsPerUser())));
        args.add(b(meta.importId().toString()));
        args.add(blob);
        for (Map.Entry<String, String> e : hash.entrySet()) {
            args.add(b(e.getKey()));
            args.add(b(e.getValue()));
        }
        Long ok = exec(c -> eval(c, CREATE, ReturnType.INTEGER, keys(meta.key()), args));
        if (ok == null || ok == 0L) {
            throw new ImportException(ImportErrorCode.SESSION_LIMIT);
        }
    }

    @Override
    public Optional<ImportSessionMeta> findMeta(SessionKey key) {
        Map<byte[], byte[]> raw = exec(c -> c.hashCommands().hGetAll(b(base(key) + "meta")));
        if (raw == null || raw.isEmpty()) return Optional.empty();
        Map<String, String> h = new LinkedHashMap<>();
        raw.forEach((k, v) -> h.put(new String(k, StandardCharsets.UTF_8), new String(v, StandardCharsets.UTF_8)));
        try {
            return Optional.of(ImportSessionMeta.fromHash(h));
        } catch (RuntimeException e) {
            // Dữ liệu phiên hỏng: coi như không có (không lộ nội dung)
            log.warn("Metadata phiên nhập hỏng, importId={}", key.importId());
            return Optional.empty();
        }
    }

    @Override
    public List<ImportRow> loadRows(SessionKey key) {
        byte[] blob = exec(c -> c.stringCommands().get(b(base(key) + "rows")));
        if (blob == null) return List.of();
        return fromJson(gunzip(blob), ROWS);
    }

    @Override
    public BeginResult beginCommit(SessionKey key, String commitToken, int totalChunks) {
        List<Object> r = exec(c -> eval(c, BEGIN, ReturnType.MULTI, keys(key), List.of(
                b(commitToken), b(Long.toString(clock.millis())), b(Long.toString(limits.heartbeatStale().toMillis())),
                b(Integer.toString(totalChunks)))));
        if (r == null || r.size() < 2) throw unavailable(null);
        long flag = (Long) r.get(0);
        String current = new String((byte[]) r.get(1), StandardCharsets.UTF_8);
        if (flag == 2L) {
            throw new ImportException(ImportErrorCode.NOT_FOUND);
        }
        return new BeginResult(flag == 1L, ImportSessionStatus.valueOf(current));
    }

    @Override
    public void releaseCommit(SessionKey key, String commitToken) {
        exec(c -> eval(c, RELEASE, ReturnType.INTEGER, keys(key), List.of(b(commitToken))));
    }

    @Override
    public boolean recordProgress(SessionKey key, String commitToken, Progress p, List<RowOutcome> outcomes,
                                  List<IssuedCredential> credentials) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("nextChunk", Integer.toString(p.nextChunk()));
        fields.put("processed", Integer.toString(p.processed()));
        fields.put("created", Integer.toString(p.created()));
        fields.put("skipped", Integer.toString(p.skipped()));
        fields.put("failed", Integer.toString(p.failed()));
        return update(key, commitToken, outcomes.isEmpty() ? new byte[0] : gzip(toJson(outcomes)), credentials, fields);
    }

    @Override
    public boolean finish(SessionKey key, String commitToken, ImportSessionStatus finalStatus, String errorCode) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("status", finalStatus.name());
        fields.put("errorCode", errorCode == null ? "" : errorCode);
        fields.put("finishedAt", Long.toString(clock.millis()));
        return update(key, commitToken, new byte[0], List.of(), fields);
    }

    private boolean update(SessionKey key, String token, byte[] resultBlob, List<IssuedCredential> creds,
                           Map<String, String> fields) {
        List<byte[]> args = new ArrayList<>();
        args.add(b(token));
        args.add(b(Long.toString(limits.sessionTtl().toMillis())));
        args.add(b(Long.toString(clock.millis())));
        args.add(b(key.importId().toString()));
        args.add(resultBlob);
        args.add(b(Integer.toString(creds.size())));
        for (IssuedCredential c : creds) args.add(b(c.login() + "\u0000" + c.secret()));
        for (Map.Entry<String, String> e : fields.entrySet()) {
            args.add(b(e.getKey()));
            args.add(b(e.getValue()));
        }
        Long ok = exec(c -> eval(c, UPDATE, ReturnType.INTEGER, keys(key), args));
        return ok != null && ok == 1L;
    }

    @Override
    public List<RowOutcome> loadOutcomes(SessionKey key) {
        List<byte[]> chunks = exec(c -> c.listCommands().lRange(b(base(key) + "result"), 0, -1));
        List<RowOutcome> all = new ArrayList<>();
        if (chunks == null) return all;
        for (byte[] chunk : chunks) all.addAll(fromJson(gunzip(chunk), OUTCOMES));
        return all;
    }

    @Override
    public Optional<List<IssuedCredential>> takeCredentials(SessionKey key) {
        List<byte[]> items = exec(c -> c.scriptingCommands().eval(b(TAKE), ReturnType.MULTI, 1, b(base(key) + "creds")));
        if (items == null || items.isEmpty()) return Optional.empty();
        List<IssuedCredential> out = new ArrayList<>();
        for (byte[] item : items) {
            String s = new String(item, StandardCharsets.UTF_8);
            int nul = s.indexOf('\u0000');
            out.add(new IssuedCredential(s.substring(0, nul), s.substring(nul + 1)));
        }
        return Optional.of(out);
    }

    @Override
    public DeleteResult delete(SessionKey key) {
        Long r = exec(c -> eval(c, DELETE, ReturnType.INTEGER, keys(key), List.of(b(key.importId().toString()),
                b(Long.toString(clock.millis())), b(Long.toString(limits.heartbeatStale().toMillis())))));
        if (r == null || r == 0L) return DeleteResult.NOT_FOUND;
        return r == 2L ? DeleteResult.COMMITTING : DeleteResult.DELETED;
    }

    // ------------------------------------------------------------------ JSON/gzip

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Không tuần tự hóa được dữ liệu phiên", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    static byte[] gzip(String text) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
            gz.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String gunzip(byte[] data) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw unavailable(e);
        }
    }
}
