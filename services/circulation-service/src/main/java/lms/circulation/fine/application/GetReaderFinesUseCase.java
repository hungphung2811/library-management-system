package lms.circulation.fine.application;

import lms.circulation.fine.domain.FineRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** UC05/UC09: xem phí phạt của độc giả; cũng là truy vấn nợ phạt để borrowing kiểm QĐ2. */
public class GetReaderFinesUseCase {

    private final FineRepository repository;

    public GetReaderFinesUseCase(FineRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ReaderFinesResponse execute(UUID readerId) {
        List<FineResponse> fines = repository.findByReader(readerId).stream().map(FineResponse::from).toList();
        return new ReaderFinesResponse(readerId, repository.sumUnpaidByReader(readerId), fines);
    }

    /** Tổng tiền phạt chưa thanh toán; 0 nghĩa là không nợ. */
    @Transactional(readOnly = true)
    public BigDecimal unpaidTotal(UUID readerId) {
        return repository.sumUnpaidByReader(readerId);
    }

    public record ReaderFinesResponse(UUID readerId, BigDecimal totalUnpaid, List<FineResponse> fines) {
    }
}
