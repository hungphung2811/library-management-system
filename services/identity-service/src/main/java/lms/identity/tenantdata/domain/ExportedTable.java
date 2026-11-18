package lms.identity.tenantdata.domain;

import java.util.List;

/** Một bảng đã xuất: tên bảng, đường dẫn file trong phần, số dòng, số byte, SHA-256 (hex) của file CSV và các cột. */
public record ExportedTable(String name, String file, long rows, long bytes, String sha256, List<String> columns) {

    public ExportedTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
