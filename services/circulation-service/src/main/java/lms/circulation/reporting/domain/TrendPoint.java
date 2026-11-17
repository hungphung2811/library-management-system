package lms.circulation.reporting.domain;

import java.time.LocalDate;

/** Một mốc của biểu đồ mượn/trả: số bản sao mượn và số bản sao trả trong bucket. */
public record TrendPoint(LocalDate bucket, long borrowed, long returned) {
}
