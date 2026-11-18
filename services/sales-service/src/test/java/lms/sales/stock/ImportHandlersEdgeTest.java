package lms.sales.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.imports.core.ImportContext;
import lms.imports.core.ImportRow;
import lms.imports.core.IssueCodes;
import lms.imports.core.OutcomeStatus;
import lms.imports.core.RowStatus;
import lms.imports.engine.RowChecks;
import lms.sales.stock.application.StockService;
import lms.sales.stock.application.imports.StockItemImportExecutor;
import lms.sales.stock.application.imports.StockItemImportHandler;
import lms.sales.support.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

/** Kiểm thử độc lập nhập mặt hàng: số khổng lồ, SKU khác hoa thường, hai chuỗi ISBN cùng một đầu sách. */
class ImportHandlersEdgeTest {

    Harness h;
    ImportHandlersTest.IsbnCatalog catalog;
    StockItemImportHandler handler;
    ImportContext ctx;

    @BeforeEach
    void setUp() {
        h = new Harness();
        catalog = new ImportHandlersTest.IsbnCatalog();
        handler = new StockItemImportHandler(catalog, h.stockPort, new StockItemImportExecutor(h.stockPort,
                new StockService(h.stockPort, h.clock), h.auditPort, h.clock));
        ctx = new ImportContext(h.tenant, h.user.toString(), UUID.randomUUID(), Set.of("SALES"), false);
    }

    private ImportRow row(int n, String... kv) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) values.put(kv[i], kv[i + 1]);
        return RowChecks.check(handler.schema().sheet(StockItemImportHandler.SHEET),
                ImportRow.of(StockItemImportHandler.SHEET, n, values));
    }

    @Test
    void absurdExponentNumbersAreRejectedQuicklyInsteadOfBurningCpu() {
        catalog.add("111", 1000);
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            List<ImportRow> out = handler.validate(ctx, List.of(
                    row(2, "isbn", "111", "sellingPrice", "1E999999999"),
                    row(3, "isbn", "111", "costPrice", "1E-999999999"),
                    row(4, "isbn", "111", "openingQuantity", "1E999999999")));
            assertThat(out).extracting(ImportRow::status).doesNotContain(RowStatus.VALID);
        });
    }

    @Test
    void skuDifferingOnlyByCaseIsADuplicateInTheFile() {
        catalog.add("111", 1000);
        catalog.add("222", 1000);
        List<ImportRow> out = handler.validate(ctx, List.of(
                row(2, "isbn", "111", "sku", "ab-1"),
                row(3, "isbn", "222", "sku", "AB-1")));
        assertThat(out.get(0).status()).isEqualTo(RowStatus.VALID);
        assertThat(out.get(1).status()).isEqualTo(RowStatus.INVALID);
        assertThat(out.get(1).issues()).extracting(i -> i.code()).contains(IssueCodes.DUPLICATE_IN_FILE);
    }

    @Test
    void twoIsbnSpellingsOfTheSameTitleNeverCreateTwoStockItemsNorFailTheChunk() {
        UUID title = catalog.add("9780000000011", 1000);
        catalog.byIsbn.put("978-0-00-000001-1", catalog.byIsbn.get("9780000000011"));
        List<ImportRow> validated = handler.validate(ctx, List.of(
                row(2, "isbn", "9780000000011", "openingQuantity", "5"),
                row(3, "isbn", "978-0-00-000001-1", "openingQuantity", "7")));

        var result = handler.commitChunk(ctx, validated.stream().filter(r -> r.status() == RowStatus.VALID).toList());

        assertThat(result.outcomes()).extracting(o -> o.status())
                .containsExactlyInAnyOrder(OutcomeStatus.CREATED, OutcomeStatus.SKIPPED_DUPLICATE);
        assertThat(h.item(title).onHand()).isEqualTo(5);
        assertThat(h.ledger()).filteredOn(m -> m.titleId().equals(title)).hasSize(1);
    }
}
