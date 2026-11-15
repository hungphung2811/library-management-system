package lms.catalog.catalog.application;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateTitleCommand(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
}
