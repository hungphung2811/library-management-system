package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.circulation.fine.domain.AdjustmentKind;
import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineAdjustmentRepository;
import lms.circulation.fine.domain.FineAdjustmentRequest;
import lms.circulation.fine.domain.FineAssessment;
import lms.circulation.fine.domain.FineLine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRefund;
import lms.circulation.fine.domain.FineRefundRepository;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.FineRuleException;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.domain.PriceSourceKind;
import lms.circulation.fine.domain.RefundMethod;
import lms.circulation.fine.domain.RestoreStatus;
import lms.circulation.policy.domain.CompensationPolicy;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tiêu chí 14 và 21. Cần Docker. Chạy: mvn test -Dtest=CompensationTenantIsolationIT -DargLine=-Dapi.version=1.44.
 * Ứng dụng chạy bằng vai trò circulation_app (chịu RLS): bậc hỏng, chính sách đền bù, đề nghị miễn/giảm, đề nghị hoàn
 * của tenant A không thấy/sửa được từ tenant B; snapshot khoản phạt lưu và đọc lại đủ; số câu SQL của danh sách không
 * tăng theo số dòng.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=it",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=200",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "app.reservation.expire-cron=-"})
@Testcontainers
class CompensationTenantIsolationIT {

    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
        registry.add("app.clients.catalog.base-url", () -> "http://localhost:1");
        registry.add("app.clients.identity.base-url", () -> "http://localhost:1");
    }

    @Autowired DamageLevelRepository levels;
    @Autowired CompensationPolicyRepository policies;
    @Autowired FineRepository fines;
    @Autowired FineAdjustmentRepository adjustments;
    @Autowired FineRefundRepository refunds;
    @Autowired TransactionTemplate tx;
    @Autowired SessionFactory sessionFactory;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
        return TenantContext.callAs(tenant, () -> tx.execute(s -> work.get()));
    }

    private static Fine lostFine(UUID reader) {
        FineAssessment a = new FineAssessment(new BigDecimal("100000"), PriceSourceKind.PURCHASE_PRICE, true,
                new BigDecimal("1.5"), null, null, new BigDecimal("150000"), "Mất", List.of("https://img/x.jpg", "http://img/y.png"));
        return Fine.issue(reader, UUID.randomUUID(), UUID.randomUUID(),
                new FineLine(FineReason.LOST, new BigDecimal("150000"), a), Instant.now());
    }

    @Test
    void bacHongVaChinhSachCuaTenantAKhongThayTuTenantB() {
        as(A, () -> levels.create(new DamageLevel("LIGHT", "Nhẹ", new BigDecimal("0.10"), 1, true), null, Instant.now()));
        as(A, () -> policies.save(new CompensationPolicy(new BigDecimal("2"), lms.circulation.policy.domain.PriceSourceKind.PURCHASE_PRICE, null,
                new BigDecimal("500000"), 15, new BigDecimal("5000")), null, Instant.now()));

        assertThat(as(A, () -> levels.findAll())).hasSize(1);
        assertThat(as(B, () -> levels.findAll())).isEmpty();
        assertThat(as(B, () -> levels.findByCode("LIGHT"))).isEmpty();
        assertThat(as(A, () -> policies.find())).isPresent();
        assertThat(as(B, () -> policies.find())).isEmpty();
        // tenant khác dùng cùng mã được; trùng mã trong cùng tenant bị từ chối
        as(B, () -> levels.create(new DamageLevel("LIGHT", "Nhẹ B", new BigDecimal("0.20"), 1, true), null, Instant.now()));
        assertThatThrownBy(() -> as(A, () -> levels.create(new DamageLevel("LIGHT", "Trùng", BigDecimal.ONE, 1, true), null, Instant.now())))
                .isInstanceOfSatisfying(CompensationRuleException.class, e -> assertThat(e.code()).isEqualTo("DAMAGE_LEVEL_CODE_EXISTS"));
    }

    @Test
    void snapshotKhoanPhatLuuDocLaiVaDeNghiMienGiamHoanCoLapTheoTenant() {
        UUID reader = UUID.randomUUID();
        Fine fine = lostFine(reader);
        as(A, () -> fines.save(fine));

        Fine loaded = as(A, () -> fines.findByIdForUpdate(fine.id())).orElseThrow();
        assertThat(loaded.assessment().priceFallback()).isTrue();
        assertThat(loaded.assessment().priceSource()).isEqualTo(PriceSourceKind.PURCHASE_PRICE);
        assertThat(loaded.assessment().evidenceUrls()).containsExactly("https://img/x.jpg", "http://img/y.png");
        assertThat(loaded.payableAmount()).isEqualByComparingTo("150000");
        assertThat(as(B, () -> fines.findByIdForUpdate(fine.id()))).isEmpty();

        FineAdjustmentRequest req = FineAdjustmentRequest.request(loaded, AdjustmentKind.REDUCE, new BigDecimal("100000"),
                "giảm", UUID.randomUUID(), Instant.now());
        as(A, () -> adjustments.save(req));
        assertThat(as(A, () -> adjustments.existsPendingForFine(fine.id()))).isTrue();
        assertThat(as(B, () -> adjustments.existsPendingForFine(fine.id()))).isFalse();
        assertThat(as(B, () -> adjustments.search(new FineAdjustmentRepository.Criteria(null, null, null, 0, 20)).items())).isEmpty();
        // một khoản chỉ có một đề nghị chờ (unique một phần ở DB)
        FineAdjustmentRequest dup = FineAdjustmentRequest.request(loaded, AdjustmentKind.WAIVE, null, "trùng", UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> as(A, () -> adjustments.save(dup))).isInstanceOfSatisfying(FineRuleException.class,
                e -> assertThat(e.code()).isEqualTo("ADJUSTMENT_ALREADY_PENDING"));

        // áp miễn/giảm rồi thu: tổng nợ theo payableAmount, REDUCED còn nợ
        FineAdjustmentRequest approved = as(A, () -> adjustments.findByIdForUpdate(req.id())).orElseThrow();
        approved.approve(UUID.randomUUID(), null, Instant.now());
        Fine toReduce = as(A, () -> fines.findByIdForUpdate(fine.id())).orElseThrow();
        toReduce.applyAdjustment(AdjustmentKind.REDUCE, approved.payableAmount(), approved.requestedBy(), approved.decidedBy(), Instant.now());
        as(A, () -> fines.save(toReduce));
        as(A, () -> adjustments.save(approved));
        assertThat(as(A, () -> fines.sumUnpaidByReader(reader))).isEqualByComparingTo("100000");
        assertThat(as(A, () -> fines.findByIdForUpdate(fine.id())).orElseThrow().status()).isEqualTo(FineStatus.REDUCED);
        assertThat(as(B, () -> fines.sumUnpaidByReader(reader))).isEqualByComparingTo("0");

        // hoàn khoản: lập, trùng bị unique chặn, tenant khác không thấy
        Fine paid = as(A, () -> fines.findByIdForUpdate(fine.id())).orElseThrow();
        paid.pay(Instant.now());
        as(A, () -> fines.save(paid));
        FineRefund refund = FineRefund.request(paid, LocalDate.now().minusDays(2), LocalDate.now(), 30, RestoreStatus.AVAILABLE,
                "ổn", RefundMethod.CASH, "REF", new BigDecimal("1000"), UUID.randomUUID(), Instant.now());
        as(A, () -> refunds.save(refund));
        assertThat(refund.refundAmount()).isEqualByComparingTo("99000");
        assertThat(as(A, () -> refunds.existsActiveForFine(fine.id()))).isTrue();
        assertThat(as(B, () -> refunds.existsActiveForFine(fine.id()))).isFalse();
        FineRefund again = FineRefund.request(paid, LocalDate.now().minusDays(2), LocalDate.now(), 30, RestoreStatus.AVAILABLE,
                null, RefundMethod.CASH, null, BigDecimal.ZERO, UUID.randomUUID(), Instant.now());
        assertThatThrownBy(() -> as(A, () -> refunds.save(again))).isInstanceOfSatisfying(FineRuleException.class,
                e -> assertThat(e.code()).isEqualTo("REFUND_ALREADY_EXISTS"));
    }

    @Test
    void soCauSqlCuaDanhSachDeNghiKhongTangTheoSoDong() {
        UUID reader = UUID.randomUUID();
        for (int i = 0; i < 2; i++) saveAdjustment(reader);
        long few = statementsFor(() -> adjustments.search(new FineAdjustmentRepository.Criteria(null, null, reader, 0, 50)));
        for (int i = 0; i < 18; i++) saveAdjustment(reader);
        long many = statementsFor(() -> adjustments.search(new FineAdjustmentRepository.Criteria(null, null, reader, 0, 50)));
        assertThat(many).isEqualTo(few);

        long fewLevels = statementsFor(() -> levels.findAll());
        for (int n = 0; n < 5; n++) {
            String code = "LV" + n + "X";
            int order = n;
            as(A, () -> levels.create(new DamageLevel(code, code, new BigDecimal("0.1"), order, true), null, Instant.now()));
        }
        assertThat(statementsFor(() -> levels.findAll())).isEqualTo(fewLevels);
    }

    private void saveAdjustment(UUID reader) {
        Fine fine = lostFine(reader);
        as(A, () -> fines.save(fine));
        as(A, () -> adjustments.save(FineAdjustmentRequest.request(fine, AdjustmentKind.WAIVE, null, "x", UUID.randomUUID(), Instant.now())));
    }

    private long statementsFor(Runnable work) {
        sessionFactory.getStatistics().clear();
        as(A, () -> { work.run(); return null; });
        return sessionFactory.getStatistics().getPrepareStatementCount();
    }
}
