package lms.identity.contract.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Body các API ghi của hợp đồng (platform). Kiểm nghiệp vụ nằm ở domain để trả đúng mã lỗi (INVALID_CONTRACT...). */
public final class ContractRequests {

    private ContractRequests() {
    }

    /** Chu kỳ thanh toán: unit DAY|MONTH|YEAR, value &gt; 0. */
    public record BillingCycleRequest(String unit, Integer value) {
    }

    /** POST tạo (NHÁP) và PUT sửa (kèm {@code version} để phát hiện sửa đồng thời). */
    public record ContractRequest(@Size(max = 255) String name, List<String> modules, LocalDate startDate,
                                  LocalDate endDate, Long totalAmount, @Valid BillingCycleRequest billingCycle,
                                  @Size(max = 2000) String internalNote, Long version) {
    }

    public record ExtendRequest(LocalDate endDate, Long version) {
    }

    public record CancelRequest(@Size(max = 1000) String reason) {
    }

    /** startDate bỏ trống = ngày sau ngày kết thúc của hợp đồng nguồn. */
    public record RenewRequest(LocalDate startDate, LocalDate endDate, Long totalAmount,
                               @Valid BillingCycleRequest billingCycle, @Size(max = 255) String name,
                               @Size(max = 2000) String internalNote) {
    }

    public record PaymentRequest(LocalDate paidOn, Long amount, @Size(max = 255) String reference,
                                 @Size(max = 1000) String note) {
    }

    public record ReverseRequest(@Size(max = 1000) String note) {
    }
}
