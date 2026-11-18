package lms.integration.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Cửa sổ cố định theo phút, khóa theo tenant/application/nhóm, bộ đếm sử dụng, fail-open khi Redis lỗi. */
@ExtendWith(OutputCaptureExtension.class)
class RedisApiRateLimiterTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID APP = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final ApiCaller CALLER = new ApiCaller(TENANT, APP, "jti-1");

    /** Redis giả: đếm theo khóa, ghi lại khóa và tham số script. */
    static class FakeRedis {
        final Map<String, Long> counters = new HashMap<>();
        final List<List<Object>> keysSeen = new ArrayList<>();
        final List<List<Object>> argsSeen = new ArrayList<>();
        RuntimeException fail;
        int calls;

        StringRedisTemplate template() {
            return mock(StringRedisTemplate.class, withSettings().defaultAnswer(invocation -> {
                if (!invocation.getMethod().getName().equals("execute")) return null;
                calls++;
                if (fail != null) throw fail;
                List<Object> keys = invocation.getArgument(1);
                keysSeen.add(keys);
                List<Object> args = new ArrayList<>();
                for (int i = 2; i < invocation.getArguments().length; i++) args.add(invocation.getArgument(i));
                argsSeen.add(args);
                long first = 0;
                for (int i = 0; i < keys.size(); i++) {
                    long v = counters.merge((String) keys.get(i), 1L, Long::sum);
                    if (i == 0) first = v;
                }
                return first;
            }));
        }
    }

    /** Đồng hồ chỉnh được (UTC). */
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now;

        MutableClock(String iso) {
            now = new AtomicReference<>(Instant.parse(iso));
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    @Test
    void khoaCoDangTenantApplicationNhomVaPhutVaBoDemSuDungTheoNgay() {
        FakeRedis redis = new FakeRedis();
        MutableClock clock = new MutableClock("2026-03-05T10:20:30Z");
        RedisApiRateLimiter limiter = new RedisApiRateLimiter(redis.template(), clock, 3);
        limiter.check(CALLER, "books");
        long minute = clock.instant().toEpochMilli() / 60_000;
        assertThat(redis.keysSeen.get(0)).containsExactly(
                "lms:rl:v1:t:" + TENANT + ":a:" + APP + ":books:" + minute,
                "lms:usage:v1:t:" + TENANT + ":a:" + APP + ":20260305");
        assertThat(redis.argsSeen.get(0)).containsExactly("120", String.valueOf(35 * 86400));
    }

    @Test
    void duoiHanMucChoQuaVaVuotHanMucBiTuChoiKemRetryAfter() {
        FakeRedis redis = new FakeRedis();
        MutableClock clock = new MutableClock("2026-03-05T10:20:45Z"); // còn 15 giây tới phút kế
        RedisApiRateLimiter limiter = new RedisApiRateLimiter(redis.template(), clock, 3);
        for (int i = 1; i <= 3; i++) {
            ApiRateLimiter.Decision d = limiter.check(CALLER, "orders").orElseThrow();
            assertThat(d.allowed()).isTrue();
            assertThat(d.remaining()).isEqualTo(3 - i);
            assertThat(d.limit()).isEqualTo(3);
        }
        ApiRateLimiter.Decision over = limiter.check(CALLER, "orders").orElseThrow();
        assertThat(over.allowed()).isFalse();
        assertThat(over.remaining()).isZero();
        assertThat(over.retryAfterSeconds()).isEqualTo(15);
    }

    @Test
    void nhomVaApplicationVaPhutKhacNhauCoBoDemRieng() {
        FakeRedis redis = new FakeRedis();
        MutableClock clock = new MutableClock("2026-03-05T10:20:10Z");
        RedisApiRateLimiter limiter = new RedisApiRateLimiter(redis.template(), clock, 1);
        assertThat(limiter.check(CALLER, "books").orElseThrow().allowed()).isTrue();
        assertThat(limiter.check(CALLER, "orders").orElseThrow().allowed()).isTrue();
        assertThat(limiter.check(new ApiCaller(TENANT, UUID.randomUUID(), "j"), "books").orElseThrow().allowed())
                .isTrue();
        assertThat(limiter.check(new ApiCaller(UUID.randomUUID(), APP, "j"), "books").orElseThrow().allowed())
                .isTrue();
        assertThat(limiter.check(CALLER, "books").orElseThrow().allowed()).isFalse();
        clock.now.set(Instant.parse("2026-03-05T10:21:00Z")); // cửa sổ mới
        assertThat(limiter.check(CALLER, "books").orElseThrow().allowed()).isTrue();
    }

    @Test
    void redisLoiThiFailOpenLogMotLanVaTamBoQuaRedis(CapturedOutput output) {
        FakeRedis redis = new FakeRedis();
        redis.fail = new RedisConnectionFailureException("Connection refused 10.0.0.5:6379");
        MutableClock clock = new MutableClock("2026-03-05T10:20:10Z");
        RedisApiRateLimiter limiter = new RedisApiRateLimiter(redis.template(), clock, 3);
        assertThat(limiter.check(CALLER, "books")).isEqualTo(Optional.empty());
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.check(CALLER, "books")).isEmpty();
        }
        assertThat(redis.calls).as("trong 5 giây sau lỗi không chạm Redis nữa").isEqualTo(1);
        assertThat(output.getAll().lines().filter(l -> l.contains("fail-open"))).hasSize(1);
        assertThat(output.getAll()).doesNotContain("10.0.0.5").doesNotContain(TENANT.toString());
        // hết cửa sổ bỏ qua, Redis sống lại thì giới hạn hoạt động trở lại
        redis.fail = null;
        clock.now.set(Instant.parse("2026-03-05T10:20:16Z"));
        assertThat(limiter.check(CALLER, "books")).isPresent();
    }

    @Test
    void redisTraRongCungLaFailOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, withSettings().defaultAnswer(i -> null));
        RedisApiRateLimiter limiter = new RedisApiRateLimiter(redis, new MutableClock("2026-03-05T10:20:10Z"), 3);
        assertThat(limiter.check(CALLER, "books")).isEmpty();
    }

    @Test
    void apiCallerChiNhanClaimUuidHopLe() {
        org.springframework.security.oauth2.jwt.Jwt good = jwt(TENANT.toString(), APP.toString());
        assertThat(ApiCaller.from(good)).isEqualTo(new ApiCaller(TENANT, APP, "jti-x"));
        assertThat(ApiCaller.from(jwt("khong-phai-uuid:*", APP.toString()))).isNull();
        assertThat(ApiCaller.from(jwt(TENANT.toString(), null))).isNull();
        assertThat(ApiCaller.from(jwt(null, APP.toString()))).isNull();
    }

    private static org.springframework.security.oauth2.jwt.Jwt jwt(String tenant, String app) {
        var b = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none").jti("jti-x")
                .claim("sub", "s");
        if (tenant != null) b.claim("tenantId", tenant);
        if (app != null) b.claim("application_id", app);
        return b.build();
    }
}
