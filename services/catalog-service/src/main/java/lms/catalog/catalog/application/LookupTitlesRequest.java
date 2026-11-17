package lms.catalog.catalog.application;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Body của POST /internal/books/lookup: 1..{@value #MAX_IDS} id đầu sách. */
public record LookupTitlesRequest(
        @NotEmpty @Size(max = MAX_IDS) List<@NotNull UUID> ids) {

    public static final int MAX_IDS = 100;
}
