package lms.circulation.reservation.infrastructure.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lms.circulation.reservation.domain.Reservation;
import lms.circulation.reservation.domain.ReservationRepository;
import lms.circulation.reservation.domain.ReservationStatus;
import lms.demo.plan.DemoPlan.ReservationPlan;
import lms.demo.plan.DemoPlan.WorkReservationPlan;
import lms.demo.runner.DemoSeedContext;
import lms.demo.runner.DemoSeedStep;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seed đặt trước demo (ADR-0018): mỗi đặt trước READY giữ đúng một bản sao mà catalog đánh dấu RESERVED (cùng id từ
 * DemoIds). Ghi thẳng qua {@link ReservationRepository}, không qua use case (use case gọi catalog/identity và phát event).
 * Hạn giữ = ngày bắt đầu giữ (ngày đặt) + số ngày giữ của kế hoạch, luôn sau hôm nay nên ExpireReservationsJob không đụng tới.
 * Idempotent theo id. Chỉ tồn tại khi profile {@code demo-seed} bật.
 * <p>
 * Mô hình tác phẩm (ADR-0026): mọi đặt trước ghi kèm {@code work_id} và {@code language} (ngôn ngữ ấn bản đã chọn). Thêm vài
 * đặt trước theo tác phẩm ở trạng thái WAITING (hàng đợi theo tác phẩm, có dòng chọn ngôn ngữ vi/en và dòng chọn mọi ngôn ngữ).
 * Dòng đã seed từ bản cũ chỉ được điền {@code language} khi còn null (không đổi gì khác).
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
            Reservation existing = repository.findById(plan.id()).orElse(null);
            if (existing == null) {
                repository.save(toReservation(plan, context.plan().today(), context.clock()));
                created++;
            } else if (existing.language() == null && plan.language() != null) {
                repository.save(Reservation.restore(existing.id(), existing.readerId(), existing.titleId(),
                        existing.workId(), plan.language(), existing.reservedAt(), existing.status(), existing.copyId(),
                        existing.holdUntil()));
                created++;
            } else {
                skipped++;
            }
        }
        for (WorkReservationPlan plan : context.plan().workReservations()) {
            if (repository.findById(plan.id()).isPresent()) {
                skipped++;
                continue;
            }
            repository.save(toReservation(plan, context.plan().today(), context.clock()));
            created++;
        }
        return SeedResult.of(created, skipped);
    }

    /** Đặt trước theo tác phẩm: WAITING ở cuối hàng đợi, chưa giữ bản sao. */
    static Reservation toReservation(WorkReservationPlan plan, LocalDate today, Clock clock) {
        return Reservation.restore(plan.id(), plan.readerId(), plan.titleId(), plan.workId(), plan.language(),
                reservedAt(today.minusDays(plan.createdDaysAgo()), clock), ReservationStatus.WAITING, null, null);
    }

    private static Instant reservedAt(LocalDate reservedDay, Clock clock) {
        Instant reservedAt = reservedDay.atTime(10, 0).atZone(clock.getZone()).toInstant();
        return reservedAt.isAfter(clock.instant()) ? clock.instant() : reservedAt;
    }

    static Reservation toReservation(ReservationPlan plan, LocalDate today, Clock clock) {
        LocalDate reservedDay = today.minusDays(plan.createdDaysAgo());
        return Reservation.restore(plan.id(), plan.readerId(), plan.titleId(), plan.workId(), plan.language(),
                reservedAt(reservedDay, clock), ReservationStatus.READY, plan.copyId(),
                reservedDay.plusDays(plan.holdDays()));
    }
}
