package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Tra bản sao theo id hoặc mã vạch, kèm giá bìa và tên đầu sách (kiểm tra trước khi mượn, tính phạt). */
public class GetCopyUseCase {

    private final BookCopyRepository repository;

    public GetCopyUseCase(BookCopyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public CopyDetailsResponse byId(UUID id) {
        return repository.findDetailsById(id).map(this::withWork)
                .orElseThrow(() -> new CopyNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public CopyDetailsResponse byBarcode(String barcode) {
        return repository.findDetailsByBarcode(barcode.trim()).map(this::withWork)
                .orElseThrow(() -> new CopyNotFoundException(barcode));
    }

    /** Thêm workId của ấn bản (một truy vấn nhẹ; ấn bản chưa gắn tác phẩm thì null). */
    private CopyDetailsResponse withWork(CopyDetails d) {
        UUID workId = repository.findWorkIdsByTitleIds(List.of(d.titleId())).get(d.titleId());
        return CopyDetailsResponse.from(d).withWorkId(workId);
    }
}
