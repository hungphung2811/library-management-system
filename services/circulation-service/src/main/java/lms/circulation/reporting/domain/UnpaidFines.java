package lms.circulation.reporting.domain;

import java.math.BigDecimal;

/** Phạt chưa thu: số khoản và tổng tiền (VND). */
public record UnpaidFines(long count, BigDecimal amount) {
}
