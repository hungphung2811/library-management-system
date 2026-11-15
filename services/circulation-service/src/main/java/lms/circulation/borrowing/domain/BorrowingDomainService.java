package lms.circulation.borrowing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Domain Service: các quy tắc QĐ1, QĐ2 và tình trạng bản sao của UC06, vượt phạm vi một aggregate. */
public class BorrowingDomainService {

    /** QĐ2: thẻ còn hạn + tài khoản không khóa (gộp trong cardValid của identity), không giữ sách quá hạn, không nợ phạt. */
    public void checkReaderEligible(boolean cardValid, long overdueItems, BigDecimal unpaidFines) {
        if (!cardValid) {
            throw new BorrowingRejectedException("CARD_INVALID", "Thẻ độc giả hết hạn hoặc tài khoản bị khóa");
        }
        if (overdueItems > 0) {
            throw new BorrowingRejectedException("OVERDUE_ITEMS",
                    "Độc giả đang giữ " + overdueItems + " cuốn sách quá hạn, phải trả trước khi mượn tiếp");
        }
        if (unpaidFines != null && unpaidFines.signum() > 0) {
            throw new BorrowingRejectedException("UNPAID_FINES",
                    "Độc giả còn nợ phí phạt " + unpaidFines.stripTrailingZeros().toPlainString() + "đ, cần thu phạt trước khi mượn tiếp");
        }
    }

    /**
     * Kiểm tra tình trạng một bản sao (UC06 4a-4c). heldFor là độc giả đang được giữ bản sao theo đặt trước READY (nếu có).
     * AVAILABLE hoặc RESERVED cho chính độc giả này thì nhận; LOANED/LOST/DAMAGED hoặc RESERVED cho người khác thì từ chối.
     */
    public void checkCopyBorrowable(UUID copyId, CopyStatus status, UUID heldFor, UUID readerId) {
        if (status == CopyStatus.LOANED || status == CopyStatus.LOST || status == CopyStatus.DAMAGED) {
            throw new BorrowingRejectedException("COPY_NOT_AVAILABLE", "Bản sao không sẵn sàng: " + copyId + " (" + status + ")");
        }
        if (heldFor != null && !heldFor.equals(readerId)) {
            throw new BorrowingRejectedException("COPY_RESERVED_FOR_OTHER", "Bản sao đang được giữ cho độc giả khác: " + copyId);
        }
        if (status == CopyStatus.RESERVED && heldFor == null) {
            throw new BorrowingRejectedException("COPY_NOT_AVAILABLE", "Bản sao đang được giữ, không thuộc độc giả này: " + copyId);
        }
    }

    /** QĐ1: số cuốn đang mượn cộng số cuốn trong phiếu không vượt hạn mức; hạn trả = ngày mượn + số ngày theo loại độc giả. */
    public BorrowingRecord borrow(BorrowRule rule, UUID userId, List<UUID> bookCopyIds, long currentActiveItems,
                                  LocalDate today) {
        int requested = bookCopyIds == null ? 0 : bookCopyIds.size();
        if (requested == 0) {
            throw new InvalidBorrowingException("Phiếu mượn phải có ít nhất một bản sao");
        }
        long remaining = rule.maxItems() - currentActiveItems;
        if (requested > remaining) {
            throw new BorrowingLimitExceededException(rule.maxItems(), remaining);
        }
        return BorrowingRecord.borrow(userId, bookCopyIds, today, rule.loanDays());
    }
}
