package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitleRepository;
import java.util.UUID;

/** Đọc mô tả và đoạn đọc thử của một đầu sách (đầu sách của tenant khác không thấy được: 404). */
public class GetTitlePreviewUseCase {

    private final TitleRepository repository;

    public GetTitlePreviewUseCase(TitleRepository repository) {
        this.repository = repository;
    }

    public TitlePreviewResponse execute(UUID id) {
        return TitlePreviewResponse.from(repository.findById(id).orElseThrow(() -> new TitleNotFoundException(id)));
    }
}
