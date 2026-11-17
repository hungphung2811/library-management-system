package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitleRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Thay mô tả và đoạn đọc thử của đầu sách (ghi cuối thắng). Chuẩn hóa và kiểm độ dài ở domain Title. */
public class UpdateTitlePreviewUseCase {

    private final TitleRepository repository;

    public UpdateTitlePreviewUseCase(TitleRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public TitlePreviewResponse execute(UUID id, String description, String previewText) {
        var current = repository.findById(id).orElseThrow(() -> new TitleNotFoundException(id));
        return TitlePreviewResponse.from(repository.save(current.withPreview(description, previewText)));
    }
}
