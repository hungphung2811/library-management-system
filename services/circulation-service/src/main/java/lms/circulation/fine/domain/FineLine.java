package lms.circulation.fine.domain;

import java.math.BigDecimal;

/** Một khoản phạt đã tính, chưa gắn với độc giả/lượt mượn. assessment null với phí trễ. */
public record FineLine(FineReason reason, BigDecimal amount, FineAssessment assessment) {

    public FineLine(FineReason reason, BigDecimal amount) {
        this(reason, amount, null);
    }
}
