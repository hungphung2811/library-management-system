package lms.circulation.fine.domain;

import java.math.BigDecimal;

/** Một khoản phạt đã tính, chưa gắn với độc giả/lượt mượn. */
public record FineLine(FineReason reason, BigDecimal amount) {
}
