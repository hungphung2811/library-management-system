package lms.circulation.reservation.infrastructure.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import lms.demo.plan.DemoPlan.ReservationPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seed đặt trước demo (ADR-0018): mỗi đặt trước READY giữ đúng một bản sao mà catalog đánh dấu RESERVED (cùng id từ
 * DemoIds). Ghi thẳng qua {@link ReservationRepository}, không qua use case (use case gọi catalog/identity và phát event).
 * Hạn giữ = ngày bắt đầu giữ (ngày đặt) + số ngày giữ của kế hoạch, luôn sau hôm nay nên ExpireReservationsJob không đụng tới.
 * Idempotent theo id. Chỉ tồn tại khi profile {@code demo-seed} bật.
 */
@Component
@Profile("demo-seed")
public class ReservationDemoSeedStep implements DemoSeedStep {

    private final ReservationRepository repository;

    public ReservationDemoSeedStep(ReservationRepository repository) {
        this.repository = repository;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public String name() {
        return "circulation.reservations";
    }

    @Override
    public SeedResult run(DemoSeedContext context) {
        int created = 0;
        int skipped = 0;
        for (ReservationPlan plan : context.plan().reservations()) {
            if (repository.findById(plan.id()).isPresent()) {
                skipped++;
                continue;
            }
            repository.save(toReservation(plan, context.plan().today(), context.clock()));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    static Reservation toReservation(ReservationPlan plan, LocalDate today, Clock clock) {
        LocalDate reservedDay = today.minusDays(plan.createdDaysAgo());
        Instant reservedAt = reservedDay.atTime(10, 0).atZone(clock.getZone()).toInstant();
        if (reservedAt.isAfter(clock.instant())) reservedAt = clock.instant();
        return Reservation.restore(plan.id(), plan.readerId(), plan.titleId(), reservedAt, ReservationStatus.READY,
                plan.copyId(), reservedDay.plusDays(plan.holdDays()));
    }
}
