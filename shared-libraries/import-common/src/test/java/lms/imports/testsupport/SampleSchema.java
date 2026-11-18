package lms.imports.testsupport;

import java.util.List;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ColumnType;
import lms.imports.core.ImportSchema;
import lms.imports.core.SheetSpec;

public final class SampleSchema {

    private SampleSchema() {
    }

    public static final List<String> HEADER = List.of("ISBN", "Tên sách *", "Giá", "Ngày xuất bản", "Định dạng", "Hoạt động", "Nhãn");

    public static ImportSchema schema() {
        return new ImportSchema("sample", 1, List.of(
                new SheetSpec("DauSach", true, List.of(
                        ColumnSpec.text("isbn", "ISBN", false, 20),
                        ColumnSpec.text("name", "Tên sách", true, 20),
                        ColumnSpec.of("price", "Giá", false, ColumnType.MONEY),
                        ColumnSpec.of("published", "Ngày xuất bản", false, ColumnType.DATE),
                        ColumnSpec.enumeration("format", "Định dạng", false, List.of("PAPERBACK", "HARDCOVER")),
                        ColumnSpec.of("active", "Hoạt động", false, ColumnType.BOOLEAN),
                        ColumnSpec.of("tags", "Nhãn", false, ColumnType.LIST))),
                new SheetSpec("BanSao", false, List.of(
                        ColumnSpec.text("isbn", "ISBN", true, 20),
                        ColumnSpec.text("barcode", "Mã vạch", true, 64)))));
    }
}
