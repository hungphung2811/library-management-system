package lms.identity.contract.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.identity.contract.domain.BillingCycle;
import lms.identity.contract.domain.Contract;
import lms.identity.contract.domain.ContractException;
import lms.identity.contract.domain.ContractException.Reason;
import lms.identity.contract.domain.ContractModule;
import lms.identity.contract.domain.ContractPayment;
import lms.identity.contract.domain.ContractPeriod;
import lms.identity.contract.domain.ContractPhase;
import lms.identity.contract.domain.ContractStatus;
import lms.identity.contract.domain.ModuleRevocationPlan;
import lms.identity.contract.domain.ReminderSchedule;
import org.junit.jupiter.api.Test;

/** T1 (ADR-0012): máy trạng thái, pha suy ra, biên múi giờ, kế hoạch thu hồi module, mốc nhắc, thanh toán bất biến. */
class ContractDomainTest {

    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    static final Duration GRACE = Duration.ofDays(7);
    static final UUID TENANT = UUID.randomUUID();
    static final UUID ACTOR = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");

    private static Contract.Content content(LocalDate start, LocalDate end, ContractModule... modules) {
        return new Contract.Content("Gói A", EnumSet.copyOf(List.of(modules)), start, end, 1_000_000L,
                new BillingCycle(BillingCycle.Unit.MONTH, 12), "ghi chú");
    }

    private static Contract draft(LocalDate start, LocalDate end) {
        return Contract.draft(TENANT, content(start, end, ContractModule.LENDING), ACTOR, NOW, VN);
    }

    // ---------------------------------------------------------------- tạo và kiểm hợp lệ

    @Test
    void taoThieuNgayKetThucLaInvalidContract() {
        assertThatThrownBy(() -> Contract.draft(TENANT, content(LocalDate.of(2026, 12, 1), null,
                ContractModule.LENDING), ACTOR, NOW, VN))
                .isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_CONTRACT));
    }

    @Test
    void ngayKetThucTruocNgayBatDauTienAmVaModuleRongBiTuChoi() {
        assertThatThrownBy(() -> draft(LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 1)))
                .isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_CONTRACT));
        Contract.Content negative = new Contract.Content("X", Set.of(ContractModule.LENDING), LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 2), -1L, null, null);
        assertThatThrownBy(() -> Contract.draft(TENANT, negative, ACTOR, NOW, VN))
                .isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_CONTRACT));
        Contract.Content noModules = new Contract.Content("X", Set.of(), LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 2), 0L, null, null);
        assertThatThrownBy(() -> Contract.draft(TENANT, noModules, ACTOR, NOW, VN))
                .isInstanceOfSatisfying(ContractException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_MODULES));
    }

    @Test
    void chuKyChiMotNuaLaKhongHopLe() {
        assertThatThrownBy(() -> BillingCycle.ofNullable("MONTH", null)).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> new BillingCycle(BillingCycle.Unit.DAY, 0)).isInstanceOf(ContractException.class);
        assertThat(BillingCycle.ofNullable(null, null)).isNull();
    }

    // ---------------------------------------------------------------- biên múi giờ

    @Test
    void endDateConHieuLucHetNgayGioVietNamVaHetLuc17hUtcCungNgay() {
        Contract c = draft(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        c.activate(ACTOR, NOW);
        // 23:59:59 giờ VN ngày 30/11 = 16:59:59 UTC ngày 30/11: còn IN_EFFECT
        assertThat(c.phase(Instant.parse("2026-11-30T16:59:59Z"), GRACE)).isEqualTo(ContractPhase.IN_EFFECT);
        // 00:00 VN ngày 1/12 = 17:00 UTC ngày 30/11 (không phải 24:00 UTC): vào ân hạn
        assertThat(c.endsAt()).isEqualTo(Instant.parse("2026-11-30T17:00:00Z"));
        assertThat(c.phase(Instant.parse("2026-11-30T17:00:00Z"), GRACE)).isEqualTo(ContractPhase.IN_GRACE);
        assertThat(ContractPeriod.endDate(c.endsAt(), VN)).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(c.startsAt()).isEqualTo(Instant.parse("2026-10-31T17:00:00Z"));
    }

    @Test
    void phaVaAnHanDocTuThamSo() {
        Contract c = draft(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        assertThat(c.phase(NOW, GRACE)).as("DRAFT không có pha").isNull();
        c.activate(ACTOR, NOW);
        assertThat(c.phase(Instant.parse("2026-10-31T16:59:59Z"), GRACE)).isEqualTo(ContractPhase.UPCOMING);
        assertThat(c.phase(Instant.parse("2026-10-31T17:00:00Z"), GRACE)).isEqualTo(ContractPhase.IN_EFFECT);
        // hết ân hạn 7 ngày: bao phủ tới ends_at + 7d (loại trừ)
        assertThat(c.covers(Instant.parse("2026-12-07T16:59:59Z"), GRACE)).isTrue();
        assertThat(c.covers(Instant.parse("2026-12-07T17:00:00Z"), GRACE)).isFalse();
        // đổi cấu hình ân hạn đổi hành vi (AC15)
        assertThat(c.covers(Instant.parse("2026-12-07T17:00:00Z"), Duration.ofDays(10))).isTrue();
    }

    // ---------------------------------------------------------------- chuyển trạng thái và R5

    @Test
    void suaDraftDuocSuaActiveChiGhiChuConLaiConflict() {
        Contract c = draft(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        c.update(content(LocalDate.of(2026, 12, 5), LocalDate.of(2027, 1, 5), ContractModule.LENDING, ContractModule.SALES),
                ACTOR, NOW, VN);
        assertThat(c.modules()).containsExactlyInAnyOrder(ContractModule.LENDING, ContractModule.SALES);

        c.activate(ACTOR, NOW);
        Contract.Content same = new Contract.Content("Gói A", c.modules(), LocalDate.of(2026, 12, 5),
                LocalDate.of(2027, 1, 5), 1_000_000L, new BillingCycle(BillingCycle.Unit.MONTH, 12), "ghi chú mới");
        assertThat(c.update(same, ACTOR, NOW, VN)).containsOnlyKeys("internalNote");
        assertThat(c.internalNote()).isEqualTo("ghi chú mới");

        Contract.Content changedPrice = new Contract.Content("Gói A", c.modules(), LocalDate.of(2026, 12, 5),
                LocalDate.of(2027, 1, 5), 2_000_000L, new BillingCycle(BillingCycle.Unit.MONTH, 12), "x");
        assertThatThrownBy(() -> c.update(changedPrice, ACTOR, NOW, VN)).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_NOT_EDITABLE));
    }

    @Test
    void kichHoatKhongPhaiDraftVaSuaHetHanDaHuyBiConflict() {
        Contract c = draft(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        c.activate(ACTOR, NOW);
        assertThatThrownBy(() -> c.activate(ACTOR, NOW)).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_NOT_EDITABLE));
        c.expire(NOW);
        assertThat(c.status()).isEqualTo(ContractStatus.EXPIRED);
        assertThatThrownBy(() -> c.update(content(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31),
                ContractModule.LENDING), ACTOR, NOW, VN)).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_NOT_EDITABLE));
        assertThatThrownBy(() -> c.cancel("x", ACTOR, NOW)).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.CONTRACT_NOT_EDITABLE));
    }

    @Test
    void huyCanLyDoVaGhiNhan() {
        Contract c = draft(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        assertThatThrownBy(() -> c.cancel("  ", ACTOR, NOW)).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_CONTRACT));
        c.cancel("Khách dừng", ACTOR, NOW);
        assertThat(c.status()).isEqualTo(ContractStatus.CANCELLED);
        assertThat(c.cancelReason()).isEqualTo("Khách dừng");
    }

    @Test
    void keoDaiChiDoiRaSauVaDuaHopDongAnHanVeInEffect() {
        Contract c = draft(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        c.activate(ACTOR, NOW);
        Instant inGrace = Instant.parse("2026-12-02T00:00:00Z");
        assertThat(c.phase(inGrace, GRACE)).isEqualTo(ContractPhase.IN_GRACE);
        assertThatThrownBy(() -> c.extend(LocalDate.of(2026, 11, 30), ACTOR, NOW, VN)).isInstanceOfSatisfying(
                ContractException.class, e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_CONTRACT));
        c.extend(LocalDate.of(2026, 12, 31), ACTOR, NOW, VN);
        assertThat(c.phase(inGrace, GRACE)).isEqualTo(ContractPhase.IN_EFFECT);
    }

    @Test
    void hopDongLegacyKhongKeoDaiVaBiCatTaiNgayBatDauCuaHopDongMoi() {
        Contract legacy = Contract.restore(UUID.randomUUID(), TENANT, "Hợp đồng chuyển tiếp", ContractStatus.ACTIVE, true,
                Instant.parse("2026-01-01T00:00:00Z"), null, 0, null, null, Set.of(ContractModule.LENDING), NOW,
                null, null, NOW, null, NOW, null, 0);
        assertThat(legacy.phase(Instant.parse("2090-01-01T00:00:00Z"), GRACE)).isEqualTo(ContractPhase.IN_EFFECT);
        assertThatThrownBy(() -> legacy.extend(LocalDate.of(2030, 1, 1), ACTOR, NOW, VN)).isInstanceOf(ContractException.class);
        Instant cut = ContractPeriod.startsAt(LocalDate.of(2026, 12, 1), VN);
        legacy.cutLegacyAt(cut, ACTOR, NOW);
        assertThat(legacy.endsAt()).isEqualTo(cut);
        assertThat(legacy.phase(cut.plusSeconds(1), GRACE)).isEqualTo(ContractPhase.IN_GRACE);
    }

    @Test
    void chongLanNuaMo() {
        Instant a = Instant.parse("2026-01-01T00:00:00Z");
        Instant b = Instant.parse("2026-02-01T00:00:00Z");
        Instant c = Instant.parse("2026-03-01T00:00:00Z");
        assertThat(ContractPeriod.overlaps(a, b, b, c)).as("nối tiếp không chồng").isFalse();
        assertThat(ContractPeriod.overlaps(a, c, b, null)).isTrue();
        assertThat(ContractPeriod.overlaps(a, null, b, c)).isTrue();
    }

    // ---------------------------------------------------------------- ModuleRevocationPlan

    @Test
    void thuHoiChiTatModuleCuaHopDongKhongTatModuleBatTayNgoaiHopDong() {
        // hợp đồng có LENDING; SALES bật tay ngoài hợp đồng -> chỉ tắt LENDING, không đụng SALES
        ModuleRevocationPlan plan = ModuleRevocationPlan.plan(Set.of(ContractModule.LENDING), Set.of(),
                Set.of(ContractModule.LENDING, ContractModule.SALES));
        assertThat(plan.toDisable()).containsExactly(ContractModule.LENDING);
        assertThat(plan.retained()).isNull();
        assertThat(plan.suspend()).isFalse();
    }

    @Test
    void thuHoiBoQuaModuleDuocHopDongKhacBaoPhuVaModuleDangTat() {
        ModuleRevocationPlan plan = ModuleRevocationPlan.plan(Set.of(ContractModule.LENDING, ContractModule.SALES),
                Set.of(ContractModule.LENDING), Set.of(ContractModule.LENDING));
        assertThat(plan.toDisable()).isEmpty();
        assertThat(plan.suspend()).isFalse();
    }

    @Test
    void moduleCuoiCungGiuMotVaSuspendKhongPhaBatBien() {
        ModuleRevocationPlan plan = ModuleRevocationPlan.plan(Set.of(ContractModule.LENDING, ContractModule.SALES),
                Set.of(), Set.of(ContractModule.LENDING, ContractModule.SALES));
        assertThat(plan.retained()).isEqualTo(ContractModule.LENDING);
        assertThat(plan.toDisable()).containsExactly(ContractModule.SALES);
        assertThat(plan.suspend()).isTrue();

        ModuleRevocationPlan single = ModuleRevocationPlan.plan(Set.of(ContractModule.SALES), Set.of(),
                Set.of(ContractModule.SALES));
        assertThat(single.toDisable()).isEmpty();
        assertThat(single.retained()).isEqualTo(ContractModule.SALES);
        assertThat(single.suspend()).isTrue();
    }

    // ---------------------------------------------------------------- ReminderSchedule

    private static final List<Integer> DAYS = List.of(30, 7, 1);

    @Test
    void chuaToiMocDauTienThiKhongNhac() {
        ReminderSchedule.Decision d = ReminderSchedule.decide(DAYS, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31),
                Set.of());
        assertThat(d.isEmpty()).isTrue();
    }

    @Test
    void toiMoc30NgayGuiMoc30() {
        ReminderSchedule.Decision d = ReminderSchedule.decide(DAYS, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31),
                Set.of());
        assertThat(d.send()).isEqualTo(30);
        assertThat(d.skipped()).isEmpty();
    }

    @Test
    void jobNgungLauChiGuiMocGapNhatVaDanhDauBoQuaMocLonHon() {
        // còn 3 ngày: đã tới hạn mốc 30 và 7, chưa tới mốc 1 -> gửi 7, bỏ qua 30
        ReminderSchedule.Decision d = ReminderSchedule.decide(DAYS, LocalDate.of(2026, 12, 28), LocalDate.of(2026, 12, 31),
                Set.of());
        assertThat(d.send()).isEqualTo(7);
        assertThat(d.skipped()).containsExactly(30);
        // còn 1 ngày (hôm nay = endDate - 1): cả ba đến hạn -> gửi 1, bỏ qua 7 và 30
        ReminderSchedule.Decision last = ReminderSchedule.decide(DAYS, LocalDate.of(2026, 12, 30), LocalDate.of(2026, 12, 31),
                Set.of());
        assertThat(last.send()).isEqualTo(1);
        assertThat(last.skipped()).containsExactlyInAnyOrder(7, 30);
    }

    @Test
    void khongGuiLaiMocDaGhiVaKhongGuiMocXaHonKhiMocGanHonDaGhi() {
        assertThat(ReminderSchedule.decide(DAYS, LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 31), Set.of(30, 7))
                .isEmpty()).isTrue();
        ReminderSchedule.Decision d = ReminderSchedule.decide(DAYS, LocalDate.of(2026, 12, 29), LocalDate.of(2026, 12, 31),
                Set.of(7));
        assertThat(d.isEmpty()).as("mốc 7 đã ghi; mốc 30 còn thiếu nhưng xa hơn nên chỉ đánh dấu bỏ qua").isTrue();
        assertThat(d.skipped()).containsExactly(30);
    }

    // ---------------------------------------------------------------- thanh toán bất biến

    @Test
    void daoToanPhanLuonBangAmGocVaMoiGocChiDaoMotLan() {
        ContractPayment original = ContractPayment.record(UUID.randomUUID(), TENANT, LocalDate.of(2026, 11, 1), 500_000,
                "REF", "note", ACTOR, NOW);
        ContractPayment reversal = ContractPayment.reversalOf(original, false, LocalDate.of(2026, 11, 2), "sai", ACTOR, NOW);
        assertThat(reversal.amount()).isEqualTo(-500_000);
        assertThat(reversal.reversesPaymentId()).isEqualTo(original.id());
        assertThat(reversal.isReversal()).isTrue();

        assertThatThrownBy(() -> ContractPayment.reversalOf(original, true, LocalDate.of(2026, 11, 2), null, ACTOR, NOW))
                .isInstanceOfSatisfying(ContractException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PAYMENT_ALREADY_REVERSED));
        assertThatThrownBy(() -> ContractPayment.reversalOf(reversal, false, LocalDate.of(2026, 11, 2), null, ACTOR, NOW))
                .isInstanceOfSatisfying(ContractException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.PAYMENT_NOT_REVERSIBLE));
    }

    @Test
    void ghiThanhToanKhongHopLeBiTuChoi() {
        assertThatThrownBy(() -> ContractPayment.record(UUID.randomUUID(), TENANT, LocalDate.of(2026, 11, 1), 0, null, null,
                ACTOR, NOW)).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_PAYMENT));
        assertThatThrownBy(() -> ContractPayment.record(UUID.randomUUID(), TENANT, null, 10, null, null, ACTOR, NOW))
                .isInstanceOf(ContractException.class);
    }
}
