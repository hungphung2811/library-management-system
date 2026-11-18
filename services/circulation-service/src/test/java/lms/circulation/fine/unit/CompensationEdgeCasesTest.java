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
import lms.circulation.fine.application.FineAdjustmentRequests;
import lms.circulation.fine.application.FineRefundUseCases;
import lms.circulation.fine.application.RequestRefundCommand;
import lms.circulation.fine.domain.AdjustmentKind;
import lms.circulation.fine.domain.ApprovalStatus;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineAssessment;
import lms.circulation.fine.domain.FineLine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRefund;
import lms.circulation.fine.domain.FineRefundRepository;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineRuleException;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.domain.RefundMode;
import lms.circulation.policy.application.GetCompensationPolicyUseCase;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.CompensationPolicySnapshot;
import lms.circulation.policy.domain.CompensationPolicy;
import org.junit.jupiter.api.Test;

/**
 * Ca biên của đền bù mà test của be-dev chưa phủ: khoản mất đã GIẢM một phần nhưng chưa thu (REDUCED) rồi tìm lại sách;
 * URL ảnh bằng chứng với scheme nguy hiểm và ký tự lạ.
 *
 */
class CompensationEdgeCasesTest {

    static final Instant NOW = Instant.parse("2026-11-18T01:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Fine reducedUnpaidLostFine(UUID reader, UUID borrowing, UUID copy) {
        Fine fine = Fine.issue(reader, borrowing, copy, new FineLine(FineReason.LOST, new BigDecimal("150000")), NOW);
        fine.applyAdjustment(AdjustmentKind.REDUCE, new BigDecimal("60000"), UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(fine.status()).isEqualTo(FineStatus.REDUCED);
        return fine;
    }

    @Test
    void khoanMatDaGiamRoiDaThuHoanTheoSoThuc() {
        UUID reader = UUID.randomUUID(), borrowing = UUID.randomUUID(), copy = UUID.randomUUID();
        Fine fine = reducedUnpaidLostFine(reader, borrowing, copy);
        fine.pay(NOW);
        FineRefund refund = FineRefund.request(fine, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 18), 30,
                lms.circulation.fine.domain.RestoreStatus.AVAILABLE, null, lms.circulation.fine.domain.RefundMethod.CASH,
                null, BigDecimal.ZERO, UUID.randomUUID(), NOW);
        assertThat(refund.mode()).isEqualTo(RefundMode.REFUND);
        assertThat(refund.refundAmount()).isEqualByComparingTo("60000");
    }

    /**
     * BUG-1: khoản mất ở trạng thái REDUCED (đã giảm, chưa thu) tìm lại sách: đề nghị hoàn được tạo ở chế độ WAIVE nhưng khi
     * duyệt gọi Fine.applyAdjustment(WAIVE) vốn chỉ chấp nhận UNPAID, nên duyệt luôn 409 FINE_NOT_ADJUSTABLE và bản sao
     * kẹt ở LOST (chỉ còn cách từ chối).
     */
    @Test
    void BUG1_khoanMatDaGiamChuaThuTimLaiDuyetHoanPhaiThanhCong() {
        UUID reader = UUID.randomUUID(), borrowing = UUID.randomUUID(), copy = UUID.randomUUID();
        Fine fine = reducedUnpaidLostFine(reader, borrowing, copy);
        List<Fine> fines = new ArrayList<>(List.of(fine));
        List<FineRefund> refunds = new ArrayList<>();
        List<String> restored = new ArrayList<>();
        FineRepository fineRepo = new FineRepository() {
            public Fine save(Fine f) { return f; }
            public boolean exists(UUID b, UUID c, FineReason r) { return true; }
            public List<Fine> findByReader(UUID r) { return fines; }
            public List<Fine> findUnpaidByReaderForUpdate(UUID r) { return fines; }
            public BigDecimal sumUnpaidByReader(UUID r) { return BigDecimal.ZERO; }
            public Optional<Fine> findByIdForUpdate(UUID id) { return Optional.of(fine); }
            public Optional<Fine> findByItemAndReason(UUID b, UUID c, FineReason r) { return Optional.of(fine); }
        };
        FineRefundRepository refundRepo = new FineRefundRepository() {
            public FineRefund save(FineRefund r) { refunds.removeIf(x -> x.id().equals(r.id())); refunds.add(r); return r; }
            public Optional<FineRefund> findByIdForUpdate(UUID id) { return refunds.stream().filter(x -> x.id().equals(id)).findFirst(); }
            public boolean existsActiveForFine(UUID fineId) { return refunds.stream().anyMatch(x -> x.status() != ApprovalStatus.REJECTED); }
            public Page search(Criteria c) { return new Page(refunds, refunds.size()); }
        };
        CompensationPolicyRepository policyRepo = new CompensationPolicyRepository() {
            public Optional<CompensationPolicySnapshot> find() { return Optional.empty(); }
            public CompensationPolicySnapshot save(CompensationPolicy p, UUID by, Instant now) { return null; }
        };
        FineRefundUseCases uc = new FineRefundUseCases(fineRepo, refundRepo, new GetCompensationPolicyUseCase(policyRepo),
                (id, target) -> restored.add(id + ":" + target), e -> { }, clock);
        UUID requester = UUID.randomUUID();
        var r = uc.request(new RequestRefundCommand(borrowing, copy, LocalDate.of(2026, 11, 15), "AVAILABLE", null, "CASH",
                null, requester));
        uc.approve(r.id(), new FineAdjustmentRequests.Decision(null), UUID.randomUUID());
        assertThat(restored).containsExactly(copy + ":AVAILABLE");
        assertThat(fine.status()).isEqualTo(FineStatus.WAIVED);
    }

    @Test
    void urlAnhSchemeNguyHiemBiTuChoi() {
        for (String bad : new String[] {"javascript:alert(1)", "JAVASCRIPT:alert(1)", "data:text/html;base64,PHNjcmlwdD4=",
                "file:///etc/passwd", "ftp://h/x", "//evil.example/x", "http:/x", "https:", "http://", "https://",
                "vbscript:x", " https://a/b", "https://a/ b", "https://a/\tb", "https://a/\nb", "https://a/\u0000b",
                "https://a/ b", "", "   "}) {
            assertThatThrownBy(() -> FineAssessment.validateUrls(List.of(bad)))
                    .as("phải từ chối: [" + bad.replace("\n", "\\n") + "]")
                    .isInstanceOfSatisfying(FineRuleException.class, e -> assertThat(e.code()).isEqualTo("INVALID_EVIDENCE_URL"));
        }
        assertThatThrownBy(() -> FineAssessment.validateUrls(java.util.Arrays.asList("https://a/b", null)))
                .isInstanceOf(FineRuleException.class);
    }

    @Test
    void urlHopLeVaDoDaiBien() {
        assertThat(FineAssessment.validateUrls(List.of("http://a", "HTTPS://A/B?x=1#y"))).hasSize(2);
        String max = "https://a/" + "x".repeat(500 - 10);
        assertThat(max).hasSize(500);
        assertThat(FineAssessment.validateUrls(List.of(max))).hasSize(1);
        assertThatThrownBy(() -> FineAssessment.validateUrls(List.of(max + "x"))).isInstanceOf(FineRuleException.class);
        assertThat(FineAssessment.validateUrls(null)).isEmpty();
        assertThat(FineAssessment.validateUrls(List.of())).isEmpty();
        assertThatThrownBy(() -> FineAssessment.validateUrls(List.of("http://a", "http://b", "http://c", "http://d")))
                .isInstanceOf(FineRuleException.class);
    }

    /** BUG-2 (thấp): ký tự Unicode trắng không tính là whitespace của Java (NBSP, ZWSP) và dấu HTML lọt qua kiểm tra URL. */
    @Test
    void BUG2_urlChuaNbspHoacDauHtmlNenBiTuChoi() {
        for (String bad : new String[] {"https://a/ b", "https://a/​b", "https://a/\"><script>alert(1)</script>"}) {
            assertThatThrownBy(() -> FineAssessment.validateUrls(List.of(bad)))
                    .as("phải từ chối: [" + bad + "]").isInstanceOf(FineRuleException.class);
        }
    }
}
