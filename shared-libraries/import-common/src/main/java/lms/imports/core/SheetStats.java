package lms.imports.core;

/** Số liệu preview của một sheet. */
public record SheetStats(String sheet, int total, int valid, int invalid, int duplicate, int warning) {
}
