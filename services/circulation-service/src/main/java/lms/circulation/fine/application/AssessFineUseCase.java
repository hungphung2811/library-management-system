package lms.circulation.fine.application;

import lms.circulation.fine.domain.Fine;
import lms.circulation.fine.domain.FineCalculator;
import lms.circulation.fine.domain.FineLine;
import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineRepository;
import lms.circulation.fine.domain.InvalidFineException;
import lms.circulation.fine.domain.PriceCandidates;
import lms.circulation.fine.domain.PriceQuote;
import lms.circulation.fine.domain.PriceSourceKind;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.CompensationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import lms.circulation.policy.application.GetCompensationPolicyUseCase;
import lms.circulation.policy.application.ResolveDamageLevelUseCase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lập phí phạt khi trả/báo mất (QĐ3, QĐ4, QĐ8). Idempotent theo (lượt mượn, bản sao, lý do): khoản đã có thì bỏ qua.
 * Chạy chung transaction với use case của borrowing gọi nó.
 * Phí trễ và ngày ân hạn theo chính sách mượn trả; hỏng theo bậc hỏng của tenant (LEGACY_DAMAGED 50% khi chưa cấu hình
 * và không kèm bậc); mất theo lostRate của chính sách đền bù (mặc định 150%); giá gốc theo priceSource (fallback giá bìa);
 * sàn/trần áp cho khoản hỏng và mất, không áp phí trễ. Mỗi khoản hỏng/mất lưu snapshot cách tính; chính sách đổi sau đó
 * không làm đổi khoản đã lập. Mọi cấu hình đọc qua use case gốc (không qua cache).
 */
public class AssessFineUseCase {

    private final FineCalculator calculator;
    private final FineRepository repository;
    private final PublishFineEventPort eventPort;
    private final Clock clock;
    private final GetCirculationPolicyUseCase policies;
    private final GetCompensationPolicyUseCase compensation;
    private final ResolveDamageLevelUseCase levels;
    private final PriceSource prices;

    public AssessFineUseCase(FineCalculator calculator, FineRepository repository, PublishFineEventPort eventPort,
                             Clock clock) {
        this(calculator, repository, eventPort, clock, GetCirculationPolicyUseCase.defaultsOnly());
    }

    public AssessFineUseCase(FineCalculator calculator, FineRepository repository, PublishFineEventPort eventPort,
                             Clock clock, GetCirculationPolicyUseCase policies) {
        this(calculator, repository, eventPort, clock, policies, GetCompensationPolicyUseCase.defaultsOnly(),
                ResolveDamageLevelUseCase.seedsOnly(), null);
    }

    public AssessFineUseCase(FineCalculator calculator, FineRepository repository, PublishFineEventPort eventPort,
                             Clock clock, GetCirculationPolicyUseCase policies, GetCompensationPolicyUseCase compensation,
                             ResolveDamageLevelUseCase levels, PriceSource prices) {
        this.calculator = calculator;
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
        this.policies = policies;
        this.compensation = compensation;
        this.levels = levels;
        this.prices = prices;
    }

    @Transactional
    public List<FineResponse> execute(AssessFineCommand command) {
        CirculationPolicyResponse policy = policies.execute();
        FineCalculator calc = policy.custom()
                ? calculator.withLateFeePerDay(BigDecimal.valueOf(policy.finePerDay())) : calculator;
        long overdueDays = policy.custom() ? Math.max(0, command.overdueDays() - policy.graceDays()) : command.overdueDays();

        List<FineLine> lines = new ArrayList<>();
        if (command.outcome() != AssessFineCommand.Outcome.LOST) {
            FineLine late = calc.lateFee(overdueDays);
            if (late != null) lines.add(late);
        }
        if (command.outcome() == AssessFineCommand.Outcome.RETURNED_DAMAGED) {
            addIfPresent(lines, damageLine(command, calc));
        } else if (command.outcome() == AssessFineCommand.Outcome.LOST) {
            addIfPresent(lines, lossLine(command, calc));
        }

        Instant now = Instant.now(clock);
        List<FineResponse> issued = new ArrayList<>();
        for (FineLine line : lines) {
            if (repository.exists(command.borrowingId(), command.bookCopyId(), line.reason())) {
                continue;
            }
            Fine fine = Fine.issue(command.readerId(), command.borrowingId(), command.bookCopyId(), line, now);
            repository.save(fine);
            fine.pullEvents().forEach(eventPort::publish);
            issued.add(FineResponse.from(fine));
        }
        return issued;
    }

    private static void addIfPresent(List<FineLine> lines, FineLine line) {
        if (line != null) lines.add(line);
    }

    private FineLine damageLine(AssessFineCommand command, FineCalculator calc) {
        ResolveDamageLevelUseCase.Resolution level = levels.resolve(command.damageLevelCode());
        BigDecimal rate = level.legacy() ? calc.defaultDamagedRate() : level.rate();
        CompensationPolicyResponse comp = compensation.execute();
        PriceQuote quote = quote(command, comp);
        return calc.compensation(FineReason.DAMAGED, quote, rate, level.code(), level.label(), comp.minAmount(),
                comp.maxAmount(), command.damageNote(), command.evidenceUrls());
    }

    private FineLine lossLine(AssessFineCommand command, FineCalculator calc) {
        CompensationPolicyResponse comp = compensation.execute();
        BigDecimal rate = comp.custom() ? comp.lostRate() : calc.defaultLostRate();
        PriceQuote quote = quote(command, comp);
        return calc.compensation(FineReason.LOST, quote, rate, null, null, comp.minAmount(), comp.maxAmount(),
                command.damageNote(), command.evidenceUrls());
    }

    private PriceQuote quote(AssessFineCommand command, CompensationPolicyResponse comp) {
        PriceSourceKind preferred = PriceSourceKind.valueOf(comp.priceSource());
        PriceCandidates candidates;
        if (command.coverPrice() != null) {
            candidates = new PriceCandidates(command.coverPrice(), null);
        } else if (prices != null) {
            PriceSource.Prices p = prices.candidates(command.bookCopyId());
            candidates = new PriceCandidates(p.coverPrice(), p.purchasePrice());
        } else {
            throw new InvalidFineException("Không có nguồn giá để tính đền bù");
        }
        return candidates.select(preferred);
    }
}
