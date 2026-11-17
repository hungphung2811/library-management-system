package lms.identity.contract.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Đổi qua lại giữa ngày (giờ VN) ở API và mốc UTC trong DB (ADR-0012 mục 2.2). Hợp đồng nửa mở
 * {@code [starts_at, ends_at)}: ngày kết thúc D còn hiệu lực hết ngày D theo giờ VN, {@code ends_at} = 00:00 VN của D+1.
 */
public final class ContractPeriod {

    private ContractPeriod() {
    }

    public static Instant startsAt(LocalDate startDate, ZoneId zone) {
        return startDate.atStartOfDay(zone).toInstant();
    }

    public static Instant endsAt(LocalDate endDate, ZoneId zone) {
        return endDate.plusDays(1).atStartOfDay(zone).toInstant();
    }

    public static LocalDate startDate(Instant startsAt, ZoneId zone) {
        return startsAt.atZone(zone).toLocalDate();
    }

    /** Ngày kết thúc (bao gồm) theo giờ VN; null nếu không có {@code endsAt}. */
    public static LocalDate endDate(Instant endsAt, ZoneId zone) {
        return endsAt == null ? null : endsAt.atZone(zone).toLocalDate().minusDays(1);
    }

    /** Hai khoảng nửa mở có giao nhau; null ở cuối nghĩa là vô hạn. */
    public static boolean overlaps(Instant startA, Instant endA, Instant startB, Instant endB) {
        boolean aBeforeB = endA != null && !endA.isAfter(startB);
        boolean bBeforeA = endB != null && !endB.isAfter(startA);
        return !aBeforeB && !bBeforeA;
    }
}
