package lms.circulation.fine.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineCalculator;
import lms.circulation.fine.domain.FineLine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRates;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.domain.InvalidFineException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FineCalculatorTest {

    private final FineCalculator calculator = new FineCalculator(FineRates.standard());

    private static BigDecimal vnd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void traDungHanNguyenVenKhongPhat() {
        assertThat(calculator.assessReturn(0, vnd("100000"), false)).isEmpty();
    }

    @Test
    void traTre3NgayPhat15000QD3() {
        List<FineLine> lines = calculator.assessReturn(3, vnd("100000"), false);

        assertThat(lines).containsExactly(new FineLine(FineReason.LATE_RETURN, vnd("15000")));
    }

    @Test
    void honGiaBia200000TraDungHanPhat100000QD4() {
        List<FineLine> lines = calculator.assessReturn(0, vnd("200000"), true);

        assertThat(lines).containsExactly(new FineLine(FineReason.DAMAGED, vnd("100000")));
    }

    @Test
    void honMaTre2NgayPhatCaHaiKhoanQD8() {
        List<FineLine> lines = calculator.assessReturn(2, vnd("200000"), true);

        assertThat(lines).containsExactly(
                new FineLine(FineReason.LATE_RETURN, vnd("10000")),
                new FineLine(FineReason.DAMAGED, vnd("100000")));
    }

    @Test
    void matSachGiaBia100000BoiThuong150000QD4() {
        assertThat(calculator.assessLoss(vnd("100000"))).containsExactly(new FineLine(FineReason.LOST, vnd("150000")));
    }

    @Test
    void matSachChiCoMotKhoanBoiThuongKhongCoPhiTreQD8() {
        assertThat(calculator.assessLoss(vnd("100000"))).hasSize(1).noneMatch(l -> l.reason() == FineReason.LATE_RETURN);
    }

    @Test
    void lamTronVndVaMucPhatTuCauHinh() {
        assertThat(calculator.assessLoss(vnd("99999.50")).get(0).amount()).isEqualByComparingTo("149999");
        FineCalculator custom = new FineCalculator(new FineRates(vnd("2000"), vnd("0.25"), vnd("2")));
        assertThat(custom.assessReturn(5, vnd("80000"), true)).extracting(FineLine::amount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(vnd("10000"), vnd("20000"));
    }

    @Test
    void khongTinhDuocPhatHongKhiThieuGiaBia() {
        assertThatThrownBy(() -> calculator.assessReturn(0, null, true)).isInstanceOf(InvalidFineException.class);
        assertThatThrownBy(() -> calculator.assessLoss(null)).isInstanceOf(InvalidFineException.class);
    }

    @Test
    void thuPhatChuyenSangPaidVaKhongThuHaiLan() {
        Fine fine = Fine.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new FineLine(FineReason.LOST, vnd("150000")), Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(fine.status()).isEqualTo(FineStatus.UNPAID);
        assertThat(fine.pullEvents()).hasSize(1);

        fine.pay(Instant.parse("2026-01-02T00:00:00Z"));

        assertThat(fine.status()).isEqualTo(FineStatus.PAID);
        assertThat(fine.paidAt()).isNotNull();
        assertThatThrownBy(() -> fine.pay(Instant.now())).isInstanceOf(InvalidFineException.class);
    }
}
