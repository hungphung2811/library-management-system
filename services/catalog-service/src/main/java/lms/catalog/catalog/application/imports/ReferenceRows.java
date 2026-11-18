package lms.catalog.catalog.application.imports;

import java.util.UUID;
import lms.catalog.catalog.domain.InvalidReferenceException;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.imports.core.ImportRow;

/**
 * Dựng {@link Reference} từ một dòng nhập bằng đúng luật domain ({@link Reference#create},
 * {@link ReferenceExtras#normalized}); dùng chung cho kiểm lúc upload và kiểm lại lúc confirm.
 */
final class ReferenceRows {

    static final String ALREADY_EXISTS = "ALREADY_EXISTS";

    private ReferenceRows() {
    }

    /** Khóa cột chứa tên: vị trí kệ dùng {@code code}, các loại khác dùng {@code name}. */
    static String nameColumn(ReferenceKind kind) {
        return kind == ReferenceKind.SHELF_LOCATION ? "code" : "name";
    }

    /** Khóa so trùng của dòng (tên chuẩn hóa chữ thường), không kiểm domain khác. */
    static String keyOf(ReferenceKind kind, ImportRow row) {
        return Reference.keyOf(row.value(nameColumn(kind)));
    }

    /** Khóa so trùng của thể loại cha ghi trong dòng, hoặc null nếu không có. */
    static String parentKeyOf(ReferenceKind kind, ImportRow row) {
        if (kind != ReferenceKind.CATEGORY) return null;
        String parent = ImportValues.text(row, "parentName");
        return parent == null ? null : Reference.keyOf(parent);
    }

    static Reference toReference(ReferenceKind kind, ImportRow row, UUID parentId) {
        String nameCol = nameColumn(kind);
        String name = row.value(nameCol);
        try {
            Reference.create(kind, name, null, null);
        } catch (InvalidReferenceException e) {
            throw new RowProblem(nameCol, kind.invalidCode(), e.getMessage());
        }
        String description = ImportValues.text(row, "description");
        try {
            Reference.create(kind, name, description, null);
        } catch (InvalidReferenceException e) {
            throw new RowProblem("description", kind.invalidCode(), e.getMessage());
        }
        ReferenceExtras extras = extras(kind, row);
        try {
            return Reference.create(kind, name, description, parentId, extras);
        } catch (InvalidReferenceException e) {
            throw new RowProblem(null, kind.invalidCode(), e.getMessage());
        }
    }

    private static ReferenceExtras extras(ReferenceKind kind, ImportRow row) {
        return switch (kind) {
            case AUTHOR -> ReferenceExtras.author(ImportValues.integer(row, "birthYear"),
                    ImportValues.integer(row, "deathYear"), ImportValues.text(row, "nationality"),
                    ImportValues.text(row, "photoUrl"), ImportValues.text(row, "website"));
            case PUBLISHER -> ReferenceExtras.publisher(ImportValues.text(row, "country"),
                    ImportValues.integer(row, "foundedYear"), ImportValues.text(row, "website"),
                    ImportValues.text(row, "address"));
            case SHELF_LOCATION -> ReferenceExtras.shelf(ImportValues.text(row, "floor"),
                    ImportValues.text(row, "zone"), ImportValues.integer(row, "capacity"));
            case CATEGORY, SERIES -> ReferenceExtras.EMPTY;
        };
    }
}
