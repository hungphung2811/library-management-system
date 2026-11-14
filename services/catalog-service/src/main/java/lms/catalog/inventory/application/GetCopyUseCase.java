package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyNotFoundException;
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
        return repository.findDetailsById(id).map(CopyDetailsResponse::from)
                .orElseThrow(() -> new CopyNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public CopyDetailsResponse byBarcode(String barcode) {
        return repository.findDetailsByBarcode(barcode.trim()).map(CopyDetailsResponse::from)
                .orElseThrow(() -> new CopyNotFoundException(barcode));
    }
}
