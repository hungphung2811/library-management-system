package lms.catalog.inventory.application;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Body của POST /internal/book-copies/lookup: 1..{@value #MAX_IDS} id bản sao. */
public record LookupCopiesRequest(
        @NotEmpty @Size(max = MAX_IDS) List<@NotNull UUID> ids) {

    public static final int MAX_IDS = 100;
}
