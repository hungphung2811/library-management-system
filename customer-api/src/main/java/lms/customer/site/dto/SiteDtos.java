package lms.customer.site.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lms.customer.web.NoControlChars;
import lms.customer.web.StrictBooleanDeserializer;

/** DTO công khai của trang giới thiệu SaaS và form liên hệ (ADR-0011 3.4). */
public final class SiteDtos {

    private SiteDtos() {
    }

    public record PageSummaryView(String slug, String title, int sortOrder) {
    }

    /** bodyHtml do system-service render (đã escape HTML thô, bỏ link nguy hiểm). */
    public record PageView(String slug, String title, String bodyHtml, Instant publishedAt) {
    }

    public enum BusinessType {
        LIBRARY, BOOKSTORE, OTHER
    }

    /**
     * Body form liên hệ/dùng thử. {@code consent} dùng Boolean để thiếu trường trả CONSENT_REQUIRED (không phải
     * VALIDATION_FAILED); chỉ nhận boolean JSON tường minh. Các trường văn bản cấm ký tự điều khiển (message cho phép
     * xuống dòng và tab). {@code website} là honeypot (ẩn ở FE): KHÔNG ràng buộc gì (không @Size) để giá trị dài của bot vẫn nhận
     * 201 "thành công giả" như mọi giá trị khác, và bị âm thầm bỏ qua.
     */
    public record ContactRequest(
            @NotBlank @Size(max = 100) @NoControlChars String fullName,
            @NotBlank @Email @Size(max = 254) @NoControlChars String email,
            @Size(max = 20) @NoControlChars String phone,
            @NotBlank @Size(max = 200) @NoControlChars String organization,
            @NotNull BusinessType businessType,
            @NotBlank @Size(max = 2000) @NoControlChars(allowLineBreaksAndTabs = true) String message,
            @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean consent,
            String website) {

        /** Không in email/điện thoại/lời nhắn (dữ liệu cá nhân) khi bị log nhầm. */
        @Override
        public String toString() {
            return "ContactRequest[***]";
        }
    }

    /** Dữ liệu gửi sang system-service (không có honeypot, consent luôn true). */
    public record ContactSubmission(String fullName, String email, String phone, String organization,
                                    BusinessType businessType, String message, boolean consent) {

        @Override
        public String toString() {
            return "ContactSubmission[***]";
        }
    }
}
