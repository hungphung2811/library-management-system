package lms.security.permission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CachingPermissionKeyRegistrarTest {

    /** Đồng hồ chỉnh tay để kiểm thử cooldown. */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static class FakeRegistrar extends CachingPermissionKeyRegistrar {
        final List<String> calls = new ArrayList<>();
        boolean fail;

        FakeRegistrar(Clock clock) {
            super(Runnable::run, clock, Duration.ofSeconds(30));
        }

        @Override
        protected void doRegister(EndpointDescriptor descriptor) {
            calls.add(descriptor.key());
            if (fail) throw new IllegalStateException("identity không phản hồi");
        }
    }

    private static EndpointDescriptor descriptor(String key) {
        return new EndpointDescriptor("svc", key, "AUTO", "GET", "/x", "mô tả");
    }

    @Test
    void khoaDaDangKyThanhCongKhongGoiLai() {
        FakeRegistrar registrar = new FakeRegistrar(new MutableClock());

        registrar.ensureRegistered(descriptor("A/a"));
        registrar.ensureRegistered(descriptor("A/a"));
        registrar.ensureRegistered(descriptor("A/b"));

        assertThat(registrar.calls).containsExactly("A/a", "A/b");
        assertThat(registrar.isRegistered("A/a")).isTrue();
    }

    @Test
    void loiDangKyKhongNemRaVaChoHetCooldownMoiThuLai() {
        MutableClock clock = new MutableClock();
        FakeRegistrar registrar = new FakeRegistrar(clock);
        registrar.fail = true;

        registrar.ensureRegistered(descriptor("A/a"));
        registrar.ensureRegistered(descriptor("A/a"));
        assertThat(registrar.calls).hasSize(1);
        assertThat(registrar.isRegistered("A/a")).isFalse();

        clock.now = clock.now.plusSeconds(31);
        registrar.fail = false;
        registrar.ensureRegistered(descriptor("A/a"));

        assertThat(registrar.calls).hasSize(2);
        assertThat(registrar.isRegistered("A/a")).isTrue();
    }
}
