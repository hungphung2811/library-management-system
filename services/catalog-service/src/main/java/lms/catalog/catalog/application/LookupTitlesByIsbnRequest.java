package lms.catalog.catalog.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body của POST /internal/books/lookup-by-isbn: 1..{@value #MAX_ISBNS} ISBN (ADR-0024, nhập đầu sách/mặt hàng từ Excel). */
public record LookupTitlesByIsbnRequest(
        @NotEmpty @Size(max = MAX_ISBNS) List<@NotBlank @Size(max = 20) String> isbns) {

    public static final int MAX_ISBNS = 5000;
}
