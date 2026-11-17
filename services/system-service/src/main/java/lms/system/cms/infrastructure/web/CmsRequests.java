package lms.system.cms.infrastructure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lms.system.cms.application.ContactRequestRepositoryPort;
import lms.system.cms.application.ManageCmsPagesUseCase;
import lms.system.cms.application.SubmitContactRequestUseCase;
import lms.system.cms.domain.BusinessType;
import lms.system.cms.domain.ContactStatus;

/**
 * Body/query của API cms. Độ dài slug/nội dung trang cố ý để domain kiểm (trả CMS_SLUG_INVALID,
 * CMS_CONTENT_TOO_LONG thay vì VALIDATION_ERROR chung).
 */
public final class CmsRequests {

    private CmsRequests() {
    }

    public record CreatePage(
            @NotBlank String slug,
            @NotBlank String title,
            @NotNull String bodyMarkdown,
            Integer sortOrder) {

        ManageCmsPagesUseCase.CreateCommand toCommand(UUID by) {
            return new ManageCmsPagesUseCase.CreateCommand(slug, title, bodyMarkdown,
                    sortOrder == null ? 0 : sortOrder, by);
        }
    }

    /** {@code version} là phiên bản trang mà người sửa đã tải. */
    public record UpdatePage(
            @NotBlank String title,
            @NotNull String bodyMarkdown,
            @NotNull Integer sortOrder,
            @NotNull Long version) {

        ManageCmsPagesUseCase.UpdateCommand toCommand(UUID id, UUID by) {
            return new ManageCmsPagesUseCase.UpdateCommand(id, title, bodyMarkdown, sortOrder, version, by);
        }
    }

    /** Xuất bản đúng phiên bản đã xem. */
    public record PublishPage(@NotNull Long version) {
    }

    public record SubmitContact(
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Size(max = 254) String email,
            @Size(max = 20) String phone,
            @NotBlank @Size(max = 200) String organization,
            @NotBlank @Pattern(regexp = "LIBRARY|BOOKSTORE|OTHER") String businessType,
            @NotBlank @Size(max = 2000) String message,
            boolean consent) {

        SubmitContactRequestUseCase.Command toCommand() {
            return new SubmitContactRequestUseCase.Command(fullName, email, phone, organization,
                    BusinessType.valueOf(businessType), message, consent);
        }

        /** Không in dữ liệu cá nhân. */
        @Override
        public String toString() {
            return "SubmitContact[...]";
        }
    }

    public record ChangeContactStatus(@NotNull ContactStatus status, @NotNull Long version) {
    }

    /** Query tìm yêu cầu liên hệ. {@code from} gồm, {@code to} không gồm (ISO-8601 instant). */
    public record ContactSearchQuery(ContactStatus status, Instant from, Instant to, Integer page, Integer size) {

        static final int DEFAULT_SIZE = 20;
        static final int MAX_SIZE = 100;

        ContactRequestRepositoryPort.Criteria toCriteria() {
            int p = page == null || page < 0 ? 0 : page;
            int s = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
            return new ContactRequestRepositoryPort.Criteria(status, from, to, p, s);
        }
    }
}
