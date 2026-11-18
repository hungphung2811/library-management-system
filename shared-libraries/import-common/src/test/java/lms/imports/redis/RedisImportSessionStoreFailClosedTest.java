package lms.imports.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import lms.imports.core.ImportErrorCode;
import lms.imports.core.ImportException;
import lms.imports.core.ImportLimits;
import lms.imports.core.ImportRow;
import lms.imports.core.ImportSessionMeta;
import lms.imports.core.ImportSessionStatus;
import lms.imports.core.SessionKey;
import lms.imports.core.SheetStats;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** Redis chết hoặc không có: mọi thao tác phải ném 503 IMPORT_STORAGE_UNAVAILABLE, không bao giờ âm thầm bỏ qua. */
class RedisImportSessionStoreFailClosedTest {

    private static final SessionKey KEY = new SessionKey(UUID.randomUUID(), "u1", UUID.randomUUID());

    private static ImportSessionMeta meta() {
        return ImportSessionMeta.validated(KEY.importId(), "sample", 1, KEY.tenantId(), KEY.userId(), "f.xlsx", 0, 1,
                List.of(new SheetStats("DauSach", 1, 1, 0, 0, 0)), 0);
    }

    private void everyOperationFailsClosed(RedisImportSessionStore store) {
        List<Consumer<RedisImportSessionStore>> ops = List.of(
                s -> s.create(meta(), List.<ImportRow>of()),
                s -> s.findMeta(KEY),
                s -> s.loadRows(KEY),
                s -> s.beginCommit(KEY, "t", 1),
                s -> s.releaseCommit(KEY, "t"),
                s -> s.recordProgress(KEY, "t", new lms.imports.core.ImportSessionStore.Progress(1, 1, 1, 0, 0), List.of(), List.of()),
                s -> s.finish(KEY, "t", ImportSessionStatus.COMMITTED, ""),
                s -> s.loadOutcomes(KEY),
                s -> s.takeCredentials(KEY),
                s -> s.delete(KEY));
        for (Consumer<RedisImportSessionStore> op : ops) {
            assertThatThrownBy(() -> op.accept(store)).isInstanceOfSatisfying(ImportException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(ImportErrorCode.STORAGE_UNAVAILABLE);
                assertThat(e.errorCode().httpStatus()).isEqualTo(503);
                assertThat(e.errorCode().code()).isEqualTo("IMPORT_STORAGE_UNAVAILABLE");
            });
        }
    }

    @Test
    void noConnectionFactoryMeansUnavailableNotNoOp() {
        everyOperationFailsClosed(new RedisImportSessionStore(null, null, ImportLimits.defaults(), Clock.systemUTC()));
    }

    @Test
    void unreachableRedisMeansUnavailable() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        factory.afterPropertiesSet();
        try {
            everyOperationFailsClosed(new RedisImportSessionStore(factory, null, ImportLimits.defaults(), Clock.systemUTC()));
        } finally {
            factory.destroy();
        }
    }
}
