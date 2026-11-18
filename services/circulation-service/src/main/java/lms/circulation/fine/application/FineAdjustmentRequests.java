package lms.circulation.fine.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lms.circulation.fine.domain.AdjustmentKind;
import lms.circulation.fine.domain.ApprovalStatus;
import java.math.BigDecimal;
import java.util.UUID;

/** Dữ liệu vào của các thao tác miễn/giảm và hoàn khoản. */
public final class FineAdjustmentRequests {

    private FineAdjustmentRequests() {
    }

    /**
     * type WAIVE (miễn toàn phần) hoặc REDUCE (giảm một phần); payableAmount là số tiền phải thu MỚI, bắt buộc với REDUCE
     * (lớn hơn 0 và nhỏ hơn số tiền gốc), bỏ qua với WAIVE. reason bắt buộc, tối đa 500 ký tự.
     */
    public record Create(@NotNull AdjustmentKind type, BigDecimal payableAmount,
                         @NotBlank @Size(max = 500) String reason) {
    }

    /** Ghi chú của người duyệt/từ chối (tùy chọn, tối đa 500 ký tự). */
    public record Decision(@Size(max = 500) String note) {
    }

    /** Bộ lọc danh sách đề nghị miễn/giảm; mọi trường tùy chọn. size tối đa 100 (mặc định 20), page từ 0. */
    public record Search(ApprovalStatus status, UUID fineId, UUID readerId, Integer page, Integer size) {
    }
}
