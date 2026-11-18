package lms.circulation.policy.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.application.CreateDamageLevelRequest;
import lms.circulation.policy.application.CreateDamageLevelUseCase;
import lms.circulation.policy.application.DeleteDamageLevelUseCase;
import lms.circulation.policy.application.ListDamageLevelsUseCase;
import lms.circulation.policy.application.UpdateDamageLevelRequest;
import lms.circulation.policy.application.UpdateDamageLevelUseCase;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.circulation.policy.domain.DamageLevelSnapshot;
import org.junit.jupiter.api.Test;

/** Tiêu chí 7: bậc trùng mã bị từ chối, không sửa được mã, bậc đã dùng không xóa được; cộng hạt giống và giới hạn 10 bậc. */
class DamageLevelUseCasesTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneOffset.UTC);
    private final List<DamageLevelSnapshot> store = new ArrayList<>();
    private boolean used;

    private final DamageLevelRepository repo = new DamageLevelRepository() {
        public List<DamageLevelSnapshot> findAll() { return store; }
        public Optional<DamageLevelSnapshot> findByCode(String code) { return store.stream().filter(s -> s.level().code().equals(code)).findFirst(); }
        public long count() { return store.size(); }
        public DamageLevelSnapshot create(DamageLevel l, UUID by, Instant now) { var s = new DamageLevelSnapshot(l, 0, now, now, by); store.add(s); return s; }
        public DamageLevelSnapshot update(DamageLevel l, long v, UUID by, Instant now) {
            store.removeIf(s -> s.level().code().equals(l.code()));
            var s = new DamageLevelSnapshot(l, v + 1, now, now, by);
            store.add(s);
            return s;
        }
        public void delete(String code) { store.removeIf(s -> s.level().code().equals(code)); }
    };

    private final CreateDamageLevelUseCase create = new CreateDamageLevelUseCase(repo, clock);
    private final UpdateDamageLevelUseCase update = new UpdateDamageLevelUseCase(repo, clock);
    private final DeleteDamageLevelUseCase delete = new DeleteDamageLevelUseCase(repo, code -> used);
    private final ListDamageLevelsUseCase list = new ListDamageLevelsUseCase(repo);

    private static CreateDamageLevelRequest req(String code) {
        return new CreateDamageLevelRequest(code, "Nhãn " + code, new BigDecimal("0.10"), 1, true);
    }

    private static void code(Runnable r, String expected) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(CompensationRuleException.class,
                e -> assertThat(e.code()).isEqualTo(expected));
    }

    @Test
    void chuaCauHinhTraHatGiongMinorMajor() {
        var res = list.execute();
        assertThat(res.custom()).isFalse();
        assertThat(res.items()).extracting("code").containsExactly("MINOR", "MAJOR");
        assertThat(res.items().get(0).rate()).isEqualByComparingTo("0.20");
        assertThat(store).isEmpty();
    }

    @Test
    void trungMaBiTuChoiMaSaiDinhDangVaVuotGioiHanBiTuChoi() {
        create.execute(req("LIGHT"), null);
        code(() -> create.execute(req("LIGHT"), null), "DAMAGE_LEVEL_CODE_EXISTS");
        code(() -> create.execute(req("light"), null), "INVALID_DAMAGE_LEVEL");
        code(() -> create.execute(req("A"), null), "INVALID_DAMAGE_LEVEL");
        code(() -> create.execute(new CreateDamageLevelRequest("RATE9", "x", new BigDecimal("5.01"), 1, true), null), "INVALID_DAMAGE_LEVEL");
        for (int i = 2; i <= DamageLevel.MAX_LEVELS_PER_TENANT; i++) create.execute(req("L" + i + "X"), null);
        code(() -> create.execute(req("EXTRA"), null), "DAMAGE_LEVEL_LIMIT_REACHED");
    }

    @Test
    void suaMaBiTuChoiSuaNhanTiLeThuTuBatTatThanhCong() {
        create.execute(req("LIGHT"), null);
        code(() -> update.execute("LIGHT", new UpdateDamageLevelRequest("OTHER", "x", BigDecimal.ONE, 1, true, null), null),
                "DAMAGE_LEVEL_CODE_IMMUTABLE");
        var out = update.execute("LIGHT", new UpdateDamageLevelRequest("LIGHT", "Nhẹ mới", new BigDecimal("0.25"), 5, false, 0L), null);
        assertThat(out.label()).isEqualTo("Nhẹ mới");
        assertThat(out.active()).isFalse();
        assertThat(out.rate()).isEqualByComparingTo("0.25");
        code(() -> update.execute("NONE", new UpdateDamageLevelRequest(null, "x", BigDecimal.ONE, 1, true, null), null), "DAMAGE_LEVEL_NOT_FOUND");
    }

    @Test
    void bacDaDungKhongXoaDuocChuaDungXoaDuoc() {
        create.execute(req("LIGHT"), null);
        used = true;
        code(() -> delete.execute("LIGHT"), "DAMAGE_LEVEL_IN_USE");
        assertThat(store).hasSize(1);
        used = false;
        delete.execute("LIGHT");
        assertThat(store).isEmpty();
    }
}
