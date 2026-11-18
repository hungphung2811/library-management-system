package lms.catalog.catalog.application;

import java.util.List;
import java.util.Map;
import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkNotFoundException;
import lms.catalog.catalog.domain.WorkRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo ấn bản (đầu sách). Tương thích ngược (ADR-0026): không có {@code workId} thì tạo tác phẩm 1:1 từ trường của ấn bản
 * (tên, tác giả, thể loại, tác giả chuẩn, chuỗi series -> tìm bộ sách theo khóa tên, chưa có thì tạo) trong CÙNG transaction;
 * có {@code workId} thì kiểm tác phẩm tồn tại cùng tenant và ấn bản mang trường cấp tác phẩm của nó (ghi gương cột cũ).
 */
public class CreateTitleUseCase {

    private final TitleRepository repository;
    private final TitleClassificationService classification;
    private final WorkRepository works;
    private final WorkService workService;
    private final TitleResponseAssembler assembler;

    public CreateTitleUseCase(TitleRepository repository) {
        this(repository, null);
    }

    public CreateTitleUseCase(TitleRepository repository, TitleClassificationService classification) {
        this(repository, classification, null, null, new TitleResponseAssembler(null, classification, null));
    }

    public CreateTitleUseCase(TitleRepository repository, TitleClassificationService classification,
                              WorkRepository works, WorkService workService, TitleResponseAssembler assembler) {
        this.repository = repository;
        this.classification = classification;
        this.works = works;
        this.workService = workService;
        this.assembler = assembler;
    }

    @Transactional
    public TitleResponse execute(CreateTitleCommand command) {
        Title title = Title.create(command.name(), command.author(), command.isbn(), command.coverPrice())
                .withExtras(command.extras()).withPart(command.partNumber(), command.partLabel());
        if (title.isbn() != null && repository.existsByIsbn(title.isbn())) {
            throw new DuplicateIsbnException(title.isbn());
        }
        if (classification == null) {
            return TitleResponse.from(repository.save(title), 0);
        }
        Title classified = classification.classify(title, command.publisherId(), command.categoryId(),
                command.authorIds());
        if (workService != null) {
            classified = attachWork(classified, command);
        }
        Title saved = repository.save(classified);
        // Ấn bản mới chưa có bản sao: số bản sao của chính nó là 0 (không cần đếm)
        return assembler.assemble(List.of(saved), Map.of()).get(0);
    }

    private Title attachWork(Title classified, CreateTitleCommand command) {
        if (command.workId() == null) {
            Work work = workService.createFromTitle(classified);
            return classified.withWork(work.id());
        }
        Work work = works.findById(command.workId()).orElseThrow(() -> new WorkNotFoundException(command.workId()));
        WorkService.WorkView view = workService.views(List.of(work.id())).get(work.id());
        return classified.withWork(work.id()).withWorkLevel(work.categoryId(), work.authorIds(),
                view == null ? null : view.seriesName(), work.seriesNumber());
    }
}
