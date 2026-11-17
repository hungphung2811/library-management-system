package lms.sales.contact.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ContactTopic;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.shared.application.PageResult;

/** Port lưu trữ yêu cầu liên hệ. Mọi truy vấn có điều kiện tenant tường minh (RLS vẫn chặn ở DB). */
public interface ShopContactRepository {

    /** Thêm mới. */
    ShopContactRequest insert(ShopContactRequest request);

    /** Cập nhật trạng thái/ẩn danh; version của domain lệch bản đã lưu thì 409 SHOP_CONTACT_STATE_CONFLICT. */
    ShopContactRequest update(ShopContactRequest request);

    Optional<ShopContactRequest> findById(UUID tenantId, UUID id);

    PageResult<ShopContactRequest> search(UUID tenantId, SearchQuery query);

    /** Yêu cầu của chính người gửi tạo sau {@code since} (phục vụ R4, R5). */
    List<ShopContactRequest> findBySenderSince(UUID tenantId, UUID customerUserId, Instant since);

    /** Tối đa {@code limit} id chưa ẩn danh có created_at trước mốc (cũ nhất trước). */
    List<UUID> findDueForAnonymization(UUID tenantId, Instant cutoff, int limit);

    /** Ẩn danh hàng loạt bằng một câu UPDATE có điều kiện; trả số dòng đã đổi (idempotent). */
    int anonymizeAll(UUID tenantId, List<UUID> ids, Instant now);

    /** {@code from} bao gồm, {@code to} loại trừ, theo created_at. */
    record SearchQuery(ContactStatus status, ContactTopic topic, Instant from, Instant to, int page, int size) {
    }
}
