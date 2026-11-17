package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.InactiveReferenceException;
import lms.catalog.catalog.domain.InvalidReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceNotFoundException;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.Title;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gắn và hiển thị phân loại chuẩn của đầu sách (nhà xuất bản, thể loại, tác giả). Tham chiếu phải tồn tại cùng
 * tenant (RLS/@TenantId lọc sẵn) và còn active; riêng giá trị đã có sẵn trên đầu sách được giữ dù mục đã ngừng dùng.
 */
public class TitleClassificationService {

    public static final int MAX_AUTHORS = 20;

    private final ReferenceRepository repository;

    public TitleClassificationService(ReferenceRepository repository) {
        this.repository = repository;
    }

    /** Hiển thị phân loại của một đầu sách (tên đã tra). */
    public record View(UUID publisherId, String publisherName, UUID categoryId, String categoryName,
                       List<AuthorRef> authors) {
        public static final View EMPTY = new View(null, null, null, null, List.of());
    }

    /**
     * Trả bản của {@code base} mang phân loại mới sau khi kiểm. {@code base} là đầu sách hiện tại (khi sửa) hoặc mới dựng
     * (khi tạo); phần đã có trên {@code base} không bị bắt buộc còn active.
     */
    public Title classify(Title base, UUID publisherId, UUID categoryId, List<UUID> authorIds) {
        List<UUID> authors = authorIds == null ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(authorIds.stream().filter(Objects::nonNull).toList()));
        if (authors.size() > MAX_AUTHORS) {
            throw new InvalidReferenceException(ReferenceKind.AUTHOR, "Tối đa " + MAX_AUTHORS + " tác giả cho một đầu sách");
        }
        if (publisherId != null && !publisherId.equals(base.publisherId())) {
            requireActive(ReferenceKind.PUBLISHER, publisherId);
        }
        if (categoryId != null && !categoryId.equals(base.categoryId())) {
            requireActive(ReferenceKind.CATEGORY, categoryId);
        }
        Set<UUID> existing = Set.copyOf(base.authorIds());
        Map<UUID, Reference> found = repository.findByIds(ReferenceKind.AUTHOR, authors).stream()
                .collect(Collectors.toMap(Reference::id, r -> r));
        for (UUID id : authors) {
            Reference r = found.get(id);
            if (r == null) throw new ReferenceNotFoundException(ReferenceKind.AUTHOR, id);
            if (!r.active() && !existing.contains(id)) throw new InactiveReferenceException(ReferenceKind.AUTHOR, id);
        }
        return base.withClassification(publisherId, categoryId, authors);
    }

    private void requireActive(ReferenceKind kind, UUID id) {
        Reference r = repository.findById(kind, id).orElseThrow(() -> new ReferenceNotFoundException(kind, id));
        if (!r.active()) throw new InactiveReferenceException(kind, id);
    }

    /** Chuỗi tác giả hiển thị: giữ nguyên nếu có; trống mà có authorIds thì ghép tên tác giả (cách nhau ", "). */
    public String authorText(String author, List<UUID> authorIds) {
        if (author != null && !author.isBlank()) return author;
        if (authorIds == null || authorIds.isEmpty()) return author;
        Map<UUID, Reference> found = repository.findByIds(ReferenceKind.AUTHOR, authorIds).stream()
                .collect(Collectors.toMap(Reference::id, r -> r));
        String joined = authorIds.stream().distinct().map(found::get).filter(Objects::nonNull).map(Reference::name)
                .collect(Collectors.joining(", "));
        return joined.isEmpty() ? author : joined;
    }

    /** Tra tên nhà xuất bản, thể loại, tác giả cho một loạt đầu sách (3 truy vấn). */
    public Map<UUID, View> describe(Collection<Title> titles) {
        Set<UUID> publisherIds = titles.stream().map(Title::publisherId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> categoryIds = titles.stream().map(Title::categoryId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> authorIds = titles.stream().flatMap(t -> t.authorIds().stream()).collect(Collectors.toSet());
        Map<UUID, String> publishers = names(ReferenceKind.PUBLISHER, publisherIds);
        Map<UUID, String> categories = names(ReferenceKind.CATEGORY, categoryIds);
        Map<UUID, String> authors = names(ReferenceKind.AUTHOR, authorIds);
        Map<UUID, View> result = new HashMap<>();
        for (Title t : titles) {
            List<AuthorRef> refs = t.authorIds().stream().filter(authors::containsKey)
                    .map(id -> new AuthorRef(id, authors.get(id))).toList();
            result.put(t.id(), new View(t.publisherId(), t.publisherId() == null ? null : publishers.get(t.publisherId()),
                    t.categoryId(), t.categoryId() == null ? null : categories.get(t.categoryId()), refs));
        }
        return result;
    }

    public View describe(Title title) {
        return describe(List.of(title)).getOrDefault(title.id(), View.EMPTY);
    }

    private Map<UUID, String> names(ReferenceKind kind, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return repository.findByIds(kind, ids).stream().collect(Collectors.toMap(Reference::id, Reference::name));
    }
}
