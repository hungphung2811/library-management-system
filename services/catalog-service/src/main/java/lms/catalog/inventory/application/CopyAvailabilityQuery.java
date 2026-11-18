package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.CopyStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API công khai của context inventory cho context khác (catalog): số bản sao sẵn có theo đầu sách. */
public class CopyAvailabilityQuery {

    private final BookCopyRepository repository;

    public CopyAvailabilityQuery(BookCopyRepository repository) {
        this.repository = repository;
    }

    /** Số bản sao sẵn có của một tác phẩm theo ngôn ngữ ấn bản ({@code language} null = ấn bản chưa khai ngôn ngữ). */
    public record WorkLanguageCount(UUID workId, String language, long count) {
    }

    /** Số bản sao sẵn có theo (tác phẩm, ngôn ngữ ấn bản), một truy vấn; ấn bản chưa gắn tác phẩm thì vắng. */
    public List<WorkLanguageCount> countAvailableByWorkAndLanguage(Collection<UUID> workIds) {
        if (workIds.isEmpty()) return List.of();
        return repository.countByWorkIds(workIds, CopyStatus.AVAILABLE).stream()
                .map(c -> new WorkLanguageCount(c.workId(), c.language(), c.count())).toList();
    }

    /** Số bản sao sẵn có theo tác phẩm (mọi ngôn ngữ, mọi ấn bản), một truy vấn. */
    public Map<UUID, Long> countAvailableByWork(Collection<UUID> workIds) {
        Map<UUID, Long> result = new HashMap<>();
        for (WorkLanguageCount c : countAvailableByWorkAndLanguage(workIds)) {
            result.merge(c.workId(), c.count(), Long::sum);
        }
        return result;
    }

    public Map<UUID, Long> countAvailable(Collection<UUID> titleIds) {
        if (titleIds.isEmpty()) return Map.of();
        return repository.countByTitleIds(titleIds, CopyStatus.AVAILABLE);
    }
}
