package lms.circulation.fine.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.fine.application.AssessFineCommand;
import lms.circulation.fine.application.AssessFineUseCase;
import lms.circulation.fine.application.CopyRestorePort;
import lms.circulation.fine.application.FineAdjustmentRequests;
import lms.circulation.fine.application.FineAdjustmentUseCases;
import lms.circulation.fine.application.FineRefundUseCases;
import lms.circulation.fine.application.FineResponse;
import lms.circulation.fine.application.PriceSource;
import lms.circulation.fine.application.RequestRefundCommand;
import lms.circulation.fine.domain.AdjustmentKind;
import lms.circulation.fine.domain.ApprovalStatus;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineAdjustmentRepository;
import lms.circulation.fine.domain.FineAdjustmentRequest;
import lms.circulation.fine.domain.FineCalculator;
import lms.circulation.fine.domain.FineRates;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRefund;
import lms.circulation.fine.domain.FineRefundRepository;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineRuleException;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.domain.FineAssessment;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.GetCompensationPolicyUseCase;
import lms.circulation.policy.application.ResolveDamageLevelUseCase;
import lms.circulation.policy.domain.CompensationPolicy;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.CompensationPolicySnapshot;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.circulation.policy.domain.DamageLevelSnapshot;
import lms.circulation.policy.domain.PriceSourceKind;
import lms.common.kernel.DomainEvent;
import org.junit.jupiter.api.Test;

/** Tiêu chí 5-8, 10-13, 15-18, 20 của đề bài tình trạng sách và đền bù, kiểm bằng fake in-memory (không Spring, không DB). */
class CompensationRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<DomainEvent> events = new ArrayList<>();
    private final List<Fine> fines = new ArrayList<>();
    private final List<FineAdjustmentRequest> adjustments = new ArrayList<>();
    private final List<FineRefund> refunds = new ArrayList<>();
    private final List<DamageLevelSnapshot> levels = new ArrayList<>();
    private CompensationPolicy storedPolicy;
    private BigDecimal purchasePrice;
    private final List<String> restored = new ArrayList<>();
    private boolean catalogFails;

    private final FineRepository fineRepo = new FineRepository() {
        public Fine save(Fine f) { fines.removeIf(x -> x.id().equals(f.id())); fines.add(f); return f; }
        public boolean exists(UUID b, UUID c, FineReason r) { return findByItemAndReason(b, c, r).isPresent(); }
        public List<Fine> findByReader(UUID r) { return fines.stream().filter(f -> f.readerId().equals(r)).toList(); }
        public List<Fine> findUnpaidByReaderForUpdate(UUID r) { return findByReader(r).stream().filter(Fine::isUnpaid).toList(); }
        public BigDecimal sumUnpaidByReader(UUID r) {
            return findUnpaidByReaderForUpdate(r).stream().map(Fine::payableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        public Optional<Fine> findByIdForUpdate(UUID id) { return fines.stream().filter(f -> f.id().equals(id)).findFirst(); }
        public Optional<Fine> findByItemAndReason(UUID b, UUID c, FineReason r) {
            return fines.stream().filter(f -> f.borrowingId().equals(b) && f.bookCopyId().equals(c) && f.reason() == r).findFirst();
        }
    };

    private final CompensationPolicyRepository policyRepo = new CompensationPolicyRepository() {
        public Optional<CompensationPolicySnapshot> find() {
            return storedPolicy == null ? Optional.empty() : Optional.of(new CompensationPolicySnapshot(storedPolicy, 1, NOW, null));
        }
        public CompensationPolicySnapshot save(CompensationPolicy p, UUID by, Instant now) { storedPolicy = p; return find().get(); }
    };

    private final DamageLevelRepository levelRepo = new DamageLevelRepository() {
        public List<DamageLevelSnapshot> findAll() { return levels; }
        public Optional<DamageLevelSnapshot> findByCode(String code) { return levels.stream().filter(l -> l.level().code().equals(code)).findFirst(); }
        public long count() { return levels.size(); }
        public DamageLevelSnapshot create(DamageLevel l, UUID by, Instant now) { var s = new DamageLevelSnapshot(l, 0, now, now, by); levels.add(s); return s; }
        public DamageLevelSnapshot update(DamageLevel l, long v, UUID by, Instant now) { return null; }
        public void delete(String code) { }
    };

    private final PriceSource prices = id -> new PriceSource.Prices(new BigDecimal("100000"), purchasePrice);
    private final GetCompensationPolicyUseCase compensation = new GetCompensationPolicyUseCase(policyRepo);
    private final AssessFineUseCase assess = new AssessFineUseCase(new FineCalculator(FineRates.standard()), fineRepo,
            events::add, clock, GetCirculationPolicyUseCase.defaultsOnly(), compensation,
            new ResolveDamageLevelUseCase(levelRepo), prices);

    private final UUID reader = UUID.randomUUID();
    private final UUID borrowing = UUID.randomUUID();
    private final UUID copy = UUID.randomUUID();

    private AssessFineCommand damaged(String level) {
        return new AssessFineCommand(reader, borrowing, copy, null, 0, AssessFineCommand.Outcome.RETURNED_DAMAGED, level, "Rách bìa",
                List.of("https://img.example/a.jpg"));
    }

    private AssessFineCommand lost() {
        return new AssessFineCommand(reader, borrowing, copy, null, 0, AssessFineCommand.Outcome.LOST, null, "Mất", null);
    }

    private void tenantLevels() {
        levels.add(new DamageLevelSnapshot(new DamageLevel("LIGHT", "Nhẹ", new BigDecimal("0.10"), 1, true), 0, NOW, NOW, null));
        levels.add(new DamageLevelSnapshot(new DamageLevel("SEVERE", "Nặng", new BigDecimal("0.70"), 2, true), 0, NOW, NOW, null));
        levels.add(new DamageLevelSnapshot(new DamageLevel("OLD", "Cũ", new BigDecimal("0.30"), 3, false), 0, NOW, NOW, null));
    }

    // ---- tiêu chí 5: giữ hành vi cũ ----
    @Test
    void tenantChuaCauHinhHongKhongBacTinh50PhanTramVaMat150PhanTram() {
        FineResponse d = assess.execute(damaged(null)).get(0);
        assertThat(d.amount()).isEqualByComparingTo("50000");
        assertThat(d.assessment().damageLevelCode()).isEqualTo("LEGACY_DAMAGED");
        assertThat(d.assessment().damageLevelLabel()).isEqualTo("Hỏng (mặc định cũ)");
        assertThat(d.assessment().rate()).isEqualByComparingTo("0.5");

        UUID otherCopy = UUID.randomUUID();
        FineResponse l = assess.execute(new AssessFineCommand(reader, borrowing, otherCopy, null, 0,
                AssessFineCommand.Outcome.LOST, null, null, null)).get(0);
        assertThat(l.amount()).isEqualByComparingTo("150000");
        assertThat(l.payableAmount()).isEqualByComparingTo("150000");
    }

    @Test
    void hatGiongMinorMajorDungDuocKhiTenantChuaCauHinh() {
        assertThat(assess.execute(damaged("MINOR")).get(0).amount()).isEqualByComparingTo("20000");
    }

    // ---- tiêu chí 6, 8 ----
    @Test
    void bacTenantTuDinhNghiaSnapshotVaMaLoiRoRang() {
        tenantLevels();
        FineResponse f = assess.execute(damaged("SEVERE")).get(0);
        assertThat(f.amount()).isEqualByComparingTo("70000");
        assertThat(f.assessment().damageLevelCode()).isEqualTo("SEVERE");
        assertThat(f.assessment().damageLevelLabel()).isEqualTo("Nặng");
        assertThat(f.assessment().evidenceUrls()).containsExactly("https://img.example/a.jpg");
        // sửa bậc sau đó không đổi khoản cũ
        levels.set(1, new DamageLevelSnapshot(new DamageLevel("SEVERE", "Rất nặng", new BigDecimal("0.90"), 2, true), 1, NOW, NOW, null));
        assertThat(fines.get(0).amount()).isEqualByComparingTo("70000");
        assertThat(fines.get(0).assessment().damageLevelLabel()).isEqualTo("Nặng");

        assertThatThrownBy(() -> assess.execute(damaged("NOPE"))).isInstanceOfSatisfying(CompensationRuleException.class,
                e -> assertThat(e.code()).isEqualTo("DAMAGE_LEVEL_UNKNOWN"));
        assertThatThrownBy(() -> assess.execute(damaged("OLD"))).isInstanceOfSatisfying(CompensationRuleException.class,
                e -> assertThat(e.code()).isEqualTo("DAMAGE_LEVEL_INACTIVE"));
        assertThatThrownBy(() -> assess.execute(damaged(null))).isInstanceOfSatisfying(CompensationRuleException.class,
                e -> assertThat(e.code()).isEqualTo("DAMAGE_LEVEL_REQUIRED"));
    }

    // ---- tiêu chí 9 ----
    @Test
    void anhBangChungToiDa3UrlHttpHttpsDuoi500KyTu() {
        assertThatThrownBy(() -> FineAssessment.validateUrls(List.of("https://a/1", "https://a/2", "https://a/3", "https://a/4")))
                .isInstanceOf(FineRuleException.class);
        assertThatThrownBy(() -> FineAssessment.validateUrls(List.of("ftp://a/1"))).isInstanceOf(FineRuleException.class);
        assertThatThrownBy(() -> FineAssessment.validateUrls(List.of("javascript:alert(1)"))).isInstanceOf(FineRuleException.class);
        assertThatThrownBy(() -> FineAssessment.validateUrls(List.of("https://a/ b"))).isInstanceOf(FineRuleException.class);
        assertThatThrownBy(() -> FineAssessment.validateUrls(List.of("https://" + "x".repeat(500)))).isInstanceOf(FineRuleException.class);
        assertThat(FineAssessment.validateUrls(List.of("http://a/1", "HTTPS://a/2"))).hasSize(2);
    }

    // ---- tiêu chí 10, 11, 12 ----
    @Test
    void giaMuaDuocDungVaFallbackGiaBiaKhiKhongCo() {
        storedPolicy = new CompensationPolicy(new BigDecimal("1.5"), PriceSourceKind.PURCHASE_PRICE, null, null, 30, BigDecimal.ZERO);
        purchasePrice = new BigDecimal("40000");
        FineResponse a = assess.execute(lost()).get(0);
        assertThat(a.amount()).isEqualByComparingTo("60000");
        assertThat(a.assessment().priceSource()).isEqualTo("PURCHASE_PRICE");
        assertThat(a.assessment().priceFallback()).isFalse();

        fines.clear();
        purchasePrice = null;
        FineResponse b = assess.execute(lost()).get(0);
        assertThat(b.amount()).isEqualByComparingTo("150000");
        assertThat(b.assessment().priceSource()).isEqualTo("COVER_PRICE");
        assertThat(b.assessment().priceFallback()).isTrue();
    }

    @Test
    void sanTranKepSoTienVaSnapshotSoTruocKep() {
        storedPolicy = new CompensationPolicy(new BigDecimal("1.5"), PriceSourceKind.COVER_PRICE, new BigDecimal("200000"), null, 30, BigDecimal.ZERO);
        FineResponse floor = assess.execute(lost()).get(0);
        assertThat(floor.amount()).isEqualByComparingTo("200000");
        assertThat(floor.assessment().amountBeforeClamp()).isEqualByComparingTo("150000");

        fines.clear();
        storedPolicy = new CompensationPolicy(new BigDecimal("1.5"), PriceSourceKind.COVER_PRICE, null, new BigDecimal("120000"), 30, BigDecimal.ZERO);
        FineResponse cap = assess.execute(lost()).get(0);
        assertThat(cap.amount()).isEqualByComparingTo("120000");
        assertThat(cap.assessment().amountBeforeClamp()).isEqualByComparingTo("150000");

        assertThatThrownBy(() -> new CompensationPolicy(BigDecimal.ONE, PriceSourceKind.COVER_PRICE, new BigDecimal("10"), new BigDecimal("5"), 30, null))
                .isInstanceOfSatisfying(CompensationRuleException.class, e -> assertThat(e.code()).isEqualTo("COMPENSATION_BOUNDS_INVALID"));
    }

    @Test
    void sanTranKhongAppChoPhiTreVaDoiChinhSachKhongDoiKhoanCu() {
        storedPolicy = new CompensationPolicy(new BigDecimal("1.5"), PriceSourceKind.COVER_PRICE, new BigDecimal("999999"), null, 30, BigDecimal.ZERO);
        List<FineResponse> out = assess.execute(new AssessFineCommand(reader, borrowing, copy, null, 2,
                AssessFineCommand.Outcome.RETURNED_DAMAGED, null, null, null));
        assertThat(out).extracting(FineResponse::reason).containsExactly("LATE_RETURN", "DAMAGED");
        assertThat(out.get(0).amount()).isEqualByComparingTo("10000"); // phí trễ, không kẹp sàn
        BigDecimal before = fines.get(1).amount();
        storedPolicy = CompensationPolicy.defaults();
        assertThat(fines.get(1).amount()).isEqualByComparingTo(before);
    }

    // ---- tiêu chí 13 ----
    @Test
    void lapLaiCungLuotMuonBanSaoLyDoKhongTaoKhoanTrung() {
        assertThat(assess.execute(lost())).hasSize(1);
        assertThat(assess.execute(lost())).isEmpty();
        assertThat(fines).hasSize(1);
    }

    // ---- tiêu chí 15, 16, 17: miễn/giảm ----
    private FineAdjustmentUseCases adjustmentUseCases() {
        FineAdjustmentRepository repo = new FineAdjustmentRepository() {
            public FineAdjustmentRequest save(FineAdjustmentRequest r) { adjustments.removeIf(x -> x.id().equals(r.id())); adjustments.add(r); return r; }
            public Optional<FineAdjustmentRequest> findByIdForUpdate(UUID id) { return adjustments.stream().filter(a -> a.id().equals(id)).findFirst(); }
            public boolean existsPendingForFine(UUID fineId) { return adjustments.stream().anyMatch(a -> a.fineId().equals(fineId) && a.status() == ApprovalStatus.PENDING); }
            public Page search(Criteria c) { return new Page(adjustments, adjustments.size()); }
        };
        return new FineAdjustmentUseCases(fineRepo, repo, events::add, clock);
    }

    private Fine unpaidFine() {
        assess.execute(lost());
        return fines.get(0);
    }

    @Test
    void nguoiDeNghiTuDuyetBiTuChoiNguoiKhacDuyetDuocMienToanPhan() {
        Fine fine = unpaidFine();
        FineAdjustmentUseCases uc = adjustmentUseCases();
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        var req = uc.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.WAIVE, null, "Hoàn cảnh khó khăn"), requester);

        assertThatThrownBy(() -> uc.approve(req.id(), null, requester)).isInstanceOfSatisfying(FineRuleException.class,
                e -> assertThat(e.code()).isEqualTo("APPROVER_MUST_DIFFER"));
        assertThatThrownBy(() -> uc.reject(req.id(), null, requester)).isInstanceOfSatisfying(FineRuleException.class,
                e -> assertThat(e.code()).isEqualTo("APPROVER_MUST_DIFFER"));

        uc.approve(req.id(), new FineAdjustmentRequests.Decision("OK"), approver);
        assertThat(fine.status()).isEqualTo(FineStatus.WAIVED);
        assertThat(fineRepo.sumUnpaidByReader(reader)).isEqualByComparingTo("0");
        assertThat(fine.amount()).isEqualByComparingTo("150000");
        assertThat(events).anyMatch(e -> e.eventType().equals("FineWaivedEvent"));
    }

    @Test
    void giamMotPhanThuDungPhanConLaiAmountGocKhongDoi() {
        Fine fine = unpaidFine();
        FineAdjustmentUseCases uc = adjustmentUseCases();
        var req = uc.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.REDUCE, new BigDecimal("50000"), "Giảm"), UUID.randomUUID());
        uc.approve(req.id(), null, UUID.randomUUID());
        assertThat(fine.status()).isEqualTo(FineStatus.REDUCED);
        assertThat(fine.amount()).isEqualByComparingTo("150000");
        assertThat(fineRepo.sumUnpaidByReader(reader)).isEqualByComparingTo("50000");
        fine.pay(NOW);
        assertThat(fine.status()).isEqualTo(FineStatus.PAID);
    }

    @Test
    void mucGiamPhaiLonHon0VaNhoHonGocKhoanDaThuKhongMienGiamDeNghiThuHaiBiTuChoi() {
        Fine fine = unpaidFine();
        FineAdjustmentUseCases uc = adjustmentUseCases();
        for (String bad : List.of("0", "150000", "200000")) {
            assertThatThrownBy(() -> uc.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.REDUCE, new BigDecimal(bad), "x"), UUID.randomUUID()))
                    .isInstanceOfSatisfying(FineRuleException.class, e -> assertThat(e.code()).isEqualTo("INVALID_ADJUSTMENT_AMOUNT"));
        }
        assertThatThrownBy(() -> uc.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.WAIVE, null, "  "), UUID.randomUUID()))
                .isInstanceOfSatisfying(FineRuleException.class, e -> assertThat(e.code()).isEqualTo("ADJUSTMENT_REASON_REQUIRED"));
        uc.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.WAIVE, null, "a"), UUID.randomUUID());
        assertThatThrownBy(() -> uc.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.WAIVE, null, "b"), UUID.randomUUID()))
                .isInstanceOfSatisfying(FineRuleException.class, e -> assertThat(e.code()).isEqualTo("ADJUSTMENT_ALREADY_PENDING"));

        fine.pay(NOW);
        FineAdjustmentUseCases uc2 = adjustmentUseCases();
        assertThatThrownBy(() -> uc2.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.WAIVE, null, "c"), UUID.randomUUID()))
                .isInstanceOfSatisfying(FineRuleException.class, e -> assertThat(e.code()).isEqualTo("FINE_NOT_ADJUSTABLE"));
    }

    // ---- tiêu chí 18, 19: hoàn khoản ----
    private FineRefundUseCases refundUseCases() {
        FineRefundRepository repo = new FineRefundRepository() {
            public FineRefund save(FineRefund r) { refunds.removeIf(x -> x.id().equals(r.id())); refunds.add(r); return r; }
            public Optional<FineRefund> findByIdForUpdate(UUID id) { return refunds.stream().filter(a -> a.id().equals(id)).findFirst(); }
            public boolean existsActiveForFine(UUID fineId) {
                return refunds.stream().anyMatch(a -> a.fineId().equals(fineId) && a.status() != ApprovalStatus.REJECTED);
            }
            public Page search(Criteria c) { return new Page(refunds, refunds.size()); }
        };
        CopyRestorePort restore = (id, target) -> {
            if (catalogFails) throw new IllegalStateException("catalog down");
            restored.add(id + ":" + target);
        };
        return new FineRefundUseCases(fineRepo, repo, compensation, restore, events::add, clock);
    }

    private RequestRefundCommand found(UUID requester, LocalDate lostOn) {
        return new RequestRefundCommand(borrowing, copy, lostOn, "AVAILABLE", "ổn", "CASH", "REF1", requester);
    }

    @Test
    void timLaiTrongHanHoanBangDaThuTruPhiXuLyDuyetGhiButToanVaDuaBanSaoVeKho() {
        storedPolicy = new CompensationPolicy(new BigDecimal("1.5"), PriceSourceKind.COVER_PRICE, null, null, 30, new BigDecimal("10000"));
        Fine fine = unpaidFine();
        fine.pay(NOW);
        FineRefundUseCases uc = refundUseCases();
        UUID requester = UUID.randomUUID();
        var r = uc.request(found(requester, LocalDate.now(clock).minusDays(5)));
        assertThat(r.mode()).isEqualTo("REFUND");
        assertThat(r.refundAmount()).isEqualByComparingTo("140000");

        assertThatThrownBy(() -> uc.approve(r.id(), null, requester)).isInstanceOfSatisfying(FineRuleException.class,
                e -> assertThat(e.code()).isEqualTo("APPROVER_MUST_DIFFER"));
        uc.approve(r.id(), null, UUID.randomUUID());
        assertThat(restored).containsExactly(copy + ":AVAILABLE");
        assertThat(events).anyMatch(e -> e.eventType().equals("FineRefundedEvent"));
        assertThatThrownBy(() -> uc.request(found(requester, LocalDate.now(clock).minusDays(5)))).isInstanceOfSatisfying(
                FineRuleException.class, e -> assertThat(e.code()).isEqualTo("REFUND_ALREADY_EXISTS"));
    }

    @Test
    void timLaiQuaHanBiTuChoi() {
        Fine fine = unpaidFine();
        fine.pay(NOW);
        assertThatThrownBy(() -> refundUseCases().request(found(UUID.randomUUID(), LocalDate.now(clock).minusDays(31))))
                .isInstanceOfSatisfying(FineRuleException.class, e -> assertThat(e.code()).isEqualTo("REFUND_WINDOW_EXPIRED"));
    }

    @Test
    void duyetHoanMaCatalogLoiNemNgoaiLeKhongPhatSuKienHoan() {
        Fine fine = unpaidFine();
        fine.pay(NOW);
        FineRefundUseCases uc = refundUseCases();
        var r = uc.request(found(UUID.randomUUID(), LocalDate.now(clock).minusDays(1)));
        events.clear();
        catalogFails = true;
        assertThatThrownBy(() -> uc.approve(r.id(), null, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        assertThat(events).noneMatch(e -> e.eventType().equals("FineRefundedEvent"));
        assertThat(restored).isEmpty();
    }

    @Test
    void khoanMatChuaThuThiDuyetHoanLaMienToanPhanKhongCoButToanHoan() {
        Fine fine = unpaidFine();
        FineRefundUseCases uc = refundUseCases();
        var r = uc.request(found(UUID.randomUUID(), LocalDate.now(clock).minusDays(1)));
        assertThat(r.mode()).isEqualTo("WAIVE");
        uc.approve(r.id(), null, UUID.randomUUID());
        assertThat(fine.status()).isEqualTo(FineStatus.WAIVED);
        assertThat(events).noneMatch(e -> e.eventType().equals("FineRefundedEvent"));
        assertThat(events).anyMatch(e -> e.eventType().equals("FineWaivedEvent"));
    }

    @Test
    void khoanMatDaGiamMotPhanHoanTheoSoThucThu() {
        Fine fine = unpaidFine();
        FineAdjustmentUseCases adj = adjustmentUseCases();
        var req = adj.request(fine.id(), new FineAdjustmentRequests.Create(AdjustmentKind.REDUCE, new BigDecimal("60000"), "g"), UUID.randomUUID());
        adj.approve(req.id(), null, UUID.randomUUID());
        fine.pay(NOW);
        var r = refundUseCases().request(found(UUID.randomUUID(), LocalDate.now(clock).minusDays(1)));
        assertThat(r.collectedAmount()).isEqualByComparingTo("60000");
        assertThat(r.refundAmount()).isEqualByComparingTo("60000");
    }
}
