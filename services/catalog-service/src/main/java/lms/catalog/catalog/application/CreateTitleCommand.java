package lms.catalog.catalog.application;

import java.math.BigDecimal;

public record CreateTitleCommand(String name, String author, String isbn, BigDecimal coverPrice) {
}
