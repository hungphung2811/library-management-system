package lms.imports.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import lms.imports.core.ImportRow;
import lms.imports.core.RowStatus;
import lms.imports.testsupport.SampleSchema;
import org.junit.jupiter.api.Test;

class RowChecksTest {

    private ImportRow check(Map<String, String> values) {
        return RowChecks.check(SampleSchema.schema().sheet("DauSach"), ImportRow.of("DauSach", 2, values));
    }

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void requiredAndTooLong() {
        ImportRow r = check(row("name", "", "isbn", "1".repeat(21)));
        assertThat(r.status()).isEqualTo(RowStatus.INVALID);
        assertThat(r.issues()).extracting("code").containsExactlyInAnyOrder("REQUIRED", "TOO_LONG");
    }

    @Test
    void normalizesDateBooleanEnumAndList() {
        ImportRow r = check(row("name", "A", "published", "09/03/2024", "active", "Có", "format", "hardcover", "tags", " a ; ;b;"));
        assertThat(r.status()).isEqualTo(RowStatus.VALID);
        assertThat(r.value("published")).isEqualTo("2024-03-09");
        assertThat(r.value("active")).isEqualTo("true");
        assertThat(r.value("format")).isEqualTo("HARDCOVER");
        assertThat(r.value("tags")).isEqualTo("a;b");
    }

    @Test
    void acceptsIsoDateAndVietnameseFalse() {
        ImportRow r = check(row("name", "A", "published", "2024-12-31", "active", "Không"));
        assertThat(r.value("published")).isEqualTo("2024-12-31");
        assertThat(r.value("active")).isEqualTo("false");
    }

    @Test
    void rejectsBadNumberDateEnumBoolean() {
        ImportRow r = check(row("name", "A", "price", "12abc", "published", "31/02/2024", "format", "PDF", "active", "maybe"));
        assertThat(r.status()).isEqualTo(RowStatus.INVALID);
        assertThat(r.issues()).extracting("code")
                .containsExactlyInAnyOrder("INVALID_NUMBER", "INVALID_DATE", "INVALID_ENUM", "INVALID_BOOLEAN");
    }

    @Test
    void keepsExistingInvalidStatusFromReader() {
        ImportRow pre = new ImportRow("DauSach", 2, row("name", "A"), RowStatus.INVALID,
                java.util.List.of(lms.imports.core.RowIssue.error("name", "CELL_TOO_LONG", "x")), Map.of());
        ImportRow r = RowChecks.check(SampleSchema.schema().sheet("DauSach"), pre);
        assertThat(r.status()).isEqualTo(RowStatus.INVALID);
        assertThat(r.issues()).hasSize(1);
    }
}
