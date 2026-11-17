package lms.identity.contract.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lms.identity.contract.domain.ContractException.Reason;

/**
 * Một dòng thanh toán thủ công, BẤT BIẾN (chỉ thêm). Sửa sai = dòng đảo TOÀN PHẦN (amount = -gốc, trỏ
 * {@code reversesPaymentId}) rồi ghi dòng đúng; mỗi dòng gốc đảo nhiều nhất một lần (ADR-0012 mục 2.2).
 */
public record ContractPayment(UUID id, UUID contractId, UUID tenantId, LocalDate paidOn, long amount,
                              String reference, String note, UUID reversesPaymentId, UUID recordedBy,
                              Instant recordedAt) {

    private static final int MAX_NOTE = 1000;
    private static final int MAX_REFERENCE = 255;

    /** Dòng thu mới (amount &gt; 0). */
    public static ContractPayment record(UUID contractId, UUID tenantId, LocalDate paidOn, long amount,
                                         String reference, String note, UUID actor, Instant now) {
        if (paidOn == null) throw new ContractException(Reason.INVALID_PAYMENT, "Thiếu ngày thanh toán");
        if (amount <= 0) throw new ContractException(Reason.INVALID_PAYMENT, "Số tiền thanh toán phải lớn hơn 0");
        requireLength(reference, MAX_REFERENCE, "Mã tham chiếu");
        requireLength(note, MAX_NOTE, "Ghi chú");
        return new ContractPayment(UUID.randomUUID(), contractId, tenantId, paidOn, amount, blankToNull(reference),
                blankToNull(note), null, actor, now);
    }

    /**
     * Dòng đảo toàn phần của {@code original}.
     *
     * @throws ContractException PAYMENT_NOT_REVERSIBLE nếu gốc là dòng đảo; PAYMENT_ALREADY_REVERSED nếu đã đảo
     */
    public static ContractPayment reversalOf(ContractPayment original, boolean alreadyReversed, LocalDate paidOn,
                                             String note, UUID actor, Instant now) {
        if (original.reversesPaymentId() != null || original.amount() <= 0) {
            throw new ContractException(Reason.PAYMENT_NOT_REVERSIBLE, "Không đảo được một dòng đảo");
        }
        if (alreadyReversed) {
            throw new ContractException(Reason.PAYMENT_ALREADY_REVERSED, "Khoản thanh toán này đã được đảo");
        }
        requireLength(note, MAX_NOTE, "Ghi chú");
        return new ContractPayment(UUID.randomUUID(), original.contractId(), original.tenantId(), paidOn,
                -original.amount(), null, blankToNull(note), original.id(), actor, now);
    }

    public boolean isReversal() {
        return reversesPaymentId != null;
    }

    private static void requireLength(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new ContractException(Reason.INVALID_PAYMENT, label + " tối đa " + max + " ký tự");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
