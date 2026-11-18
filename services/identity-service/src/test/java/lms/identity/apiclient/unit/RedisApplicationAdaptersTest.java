package lms.identity.apiclient.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.infrastructure.persistence.RedisApplicationRevocationAdapter;
import lms.identity.apiclient.infrastructure.persistence.RedisApplicationUsageAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Adapter Redis: khóa đúng mẫu mà integration-api ghi/đọc, lỗi Redis được xử lý đúng chế độ của từng adapter. */
class RedisApplicationAdaptersTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID APP = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    RedisApplicationAdaptersTest() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void usageDocMotLuotMgetVoiKhoaTheoNgayVaBoQuaGiaTriLa() {
        List<LocalDate> days = List.of(LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-17"),
                LocalDate.parse("2026-11-18"));
        when(ops.multiGet(anyList())).thenReturn(Arrays.asList("10", null, "abc"));

        var out = new RedisApplicationUsageAdapter(redis).dailyRequests(TENANT, APP, days);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(ops).multiGet(keys.capture());
        assertThat(keys.getValue()).containsExactly(
                "lms:usage:v1:t:" + TENANT + ":a:" + APP + ":20261116",
                "lms:usage:v1:t:" + TENANT + ":a:" + APP + ":20261117",
                "lms:usage:v1:t:" + TENANT + ":a:" + APP + ":20261118");
        assertThat(out).containsOnlyKeys(days.get(0)).containsEntry(days.get(0), 10L);
    }

    @Test
    void usageRedisLoiThiUsageUnavailable() {
        when(ops.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("Redis chết"));
        assertThatThrownBy(() -> new RedisApplicationUsageAdapter(redis)
                .dailyRequests(TENANT, APP, List.of(LocalDate.parse("2026-11-18"))))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.USAGE_UNAVAILABLE));
    }

    @Test
    void usagePhanHoiRongThiUsageUnavailable() {
        when(ops.multiGet(anyList())).thenReturn(null);
        assertThatThrownBy(() -> new RedisApplicationUsageAdapter(redis)
                .dailyRequests(TENANT, APP, List.of(LocalDate.parse("2026-11-18"))))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void thuHoiGhiMocGiayCongMotVoiTtl() {
        Instant at = Instant.parse("2026-11-18T10:00:00.700Z");
        new RedisApplicationRevocationAdapter(redis, Duration.ofHours(1)).revokeTokensIssuedBefore(APP, at);
        verify(ops).set("lms:revoked:v1:app:" + APP, String.valueOf(at.getEpochSecond() + 1), Duration.ofHours(1));
    }

    @Test
    void thuHoiRedisLoiThiKhongLamHongThaoTac() {
        doThrow(new RedisConnectionFailureException("Redis chết")).when(ops).set(anyString(), anyString(), eq(Duration.ofHours(1)));
        new RedisApplicationRevocationAdapter(redis, Duration.ofHours(1)).revokeTokensIssuedBefore(APP, Instant.now());
    }
}
