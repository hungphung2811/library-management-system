package lms.identity.tenant.application;

import java.util.List;

/** Origin của tenant hiện tại cho tenant admin (chỉ xem): cố ý không có id người tạo/sửa của platform admin. */
public record MyStorefrontOriginsResponse(List<Item> origins) {

    public record Item(String origin, boolean enabled) {
    }
}
