package lms.catalog.catalog.application;

import java.util.UUID;

/** Tác giả trả cho client; các trường từ birthYear trở đi là thông tin bổ sung tùy chọn (null nếu chưa nhập). */
public record AuthorResponse(UUID id, String name, String description, boolean active, UUID tenantId,
                             Integer birthYear, Integer deathYear, String nationality, String photoUrl,
                             String website) {

    public static AuthorResponse from(ReferenceResponse r) {
        var x = r.extras();
        return new AuthorResponse(r.id(), r.name(), r.description(), r.active(), r.tenantId(), x.birthYear(),
                x.deathYear(), x.nationality(), x.photoUrl(), x.website());
    }
}
