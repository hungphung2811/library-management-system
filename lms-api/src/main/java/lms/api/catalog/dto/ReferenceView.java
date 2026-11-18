package lms.api.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * Mục danh mục chuẩn (tác giả, nhà xuất bản, thể loại) trả cho FE; parentId/parentName chỉ có ở thể loại.
 * Thông tin bổ sung tùy chọn chỉ xuất hiện khi có giá trị (vắng = chưa nhập) và theo loại: tác giả có birthYear,
 * deathYear, nationality, photoUrl, website; nhà xuất bản có country, foundedYear, website, address; thể loại không có.
 */
public record ReferenceView(UUID id, String name, String description, UUID parentId, String parentName,
                            boolean active, UUID tenantId,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Integer birthYear,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Integer deathYear,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String nationality,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String photoUrl,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String website,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String country,
                            @JsonInclude(JsonInclude.Include.NON_NULL) Integer foundedYear,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String address) {

    /** Dạng 7 trường (chưa có thông tin bổ sung). */
    public ReferenceView(UUID id, String name, String description, UUID parentId, String parentName,
                         boolean active, UUID tenantId) {
        this(id, name, description, parentId, parentName, active, tenantId, null, null, null, null, null, null, null,
                null);
    }
}
