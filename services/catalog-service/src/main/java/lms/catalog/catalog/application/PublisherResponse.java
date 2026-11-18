package lms.catalog.catalog.application;

import java.util.UUID;

/** Nhà xuất bản trả cho client; các trường từ country trở đi là thông tin bổ sung tùy chọn (null nếu chưa nhập). */
public record PublisherResponse(UUID id, String name, String description, boolean active, UUID tenantId,
                                String country, Integer foundedYear, String website, String address) {

    public static PublisherResponse from(ReferenceResponse r) {
        var x = r.extras();
        return new PublisherResponse(r.id(), r.name(), r.description(), r.active(), r.tenantId(), x.country(),
                x.foundedYear(), x.website(), x.address());
    }
}
