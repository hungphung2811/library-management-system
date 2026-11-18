package lms.catalog.catalog.application.imports;

import java.util.List;
import lms.imports.core.ColumnSpec;
import lms.imports.core.ColumnType;
import lms.imports.core.ImportSchema;
import lms.imports.core.SheetSpec;

/**
 * Schema cột của 5 loại nhập catalog (ADR-0024 mục 3.4.1-3.4.3). Cột lấy từ entity/domain thật; độ dài tối đa khớp
 * domain ({@code Reference}, {@code ReferenceExtras}, {@code TitleExtras}, {@code CopyAcquisition}). Tên sheet và
 * khóa cột là hợp đồng với FE/file mẫu: không đổi sau khi phát hành.
 */
public final class CatalogImportSchemas {

    public static final String TITLES = "titles";
    public static final String AUTHORS = "authors";
    public static final String PUBLISHERS = "publishers";
    public static final String CATEGORIES = "categories";
    public static final String SHELF_LOCATIONS = "shelf-locations";

    public static final String SHEET_TITLES = "DauSach";
    public static final String SHEET_COPIES = "BanSao";

    private CatalogImportSchemas() {
    }

    private static ColumnSpec text(String key, String label, boolean required, int max, String example, String hint) {
        return new ColumnSpec(key, label, required, ColumnType.TEXT, max, example, hint, List.of());
    }

    private static ColumnSpec typed(String key, String label, boolean required, ColumnType type, String example,
                                    String hint) {
        return new ColumnSpec(key, label, required, type, 0, example, hint, List.of());
    }

    public static ImportSchema authors() {
        return ImportSchema.single(AUTHORS, 1, "TacGia", List.of(
                text("name", "Tên tác giả", true, 255, "Nguyễn Nhật Ánh", "Tên duy nhất trong thư viện (không phân biệt hoa thường)"),
                text("description", "Mô tả", false, 500, "Nhà văn Việt Nam", "Tối đa 500 ký tự"),
                typed("birthYear", "Năm sinh", false, ColumnType.INTEGER, "1955", "Từ 1 đến năm hiện tại"),
                typed("deathYear", "Năm mất", false, ColumnType.INTEGER, "", "Từ năm sinh đến năm hiện tại"),
                text("nationality", "Quốc tịch", false, 100, "Việt Nam", ""),
                text("photoUrl", "Ảnh (URL)", false, 500, "https://example.com/anh.jpg", "URL http hoặc https"),
                text("website", "Website", false, 500, "https://example.com", "URL http hoặc https")));
    }

    public static ImportSchema publishers() {
        return ImportSchema.single(PUBLISHERS, 1, "NhaXuatBan", List.of(
                text("name", "Tên nhà xuất bản", true, 255, "NXB Trẻ", "Tên duy nhất trong thư viện (không phân biệt hoa thường)"),
                text("description", "Mô tả", false, 500, "", "Tối đa 500 ký tự"),
                text("country", "Quốc gia", false, 100, "Việt Nam", ""),
                typed("foundedYear", "Năm thành lập", false, ColumnType.INTEGER, "1981", "Từ 1400 đến năm hiện tại"),
                text("website", "Website", false, 500, "https://example.com", "URL http hoặc https"),
                text("address", "Địa chỉ", false, 500, "", "Tối đa 500 ký tự")));
    }

    public static ImportSchema categories() {
        return ImportSchema.single(CATEGORIES, 1, "TheLoai", List.of(
                text("name", "Tên thể loại", true, 255, "Văn học", "Tên duy nhất trong thư viện (không phân biệt hoa thường)"),
                text("description", "Mô tả", false, 500, "", "Tối đa 500 ký tự"),
                text("parentName", "Thể loại cha", false, 255, "",
                        "Tên thể loại cha: đã có trong thư viện hoặc là một dòng khác trong file. Cây tối đa 2 cấp")));
    }

    public static ImportSchema shelfLocations() {
        return ImportSchema.single(SHELF_LOCATIONS, 1, "ViTriKe", List.of(
                text("code", "Mã vị trí kệ", true, 64, "A1-01", "Mã duy nhất trong thư viện (không phân biệt hoa thường)"),
                text("description", "Mô tả", false, 500, "", "Tối đa 500 ký tự"),
                text("floor", "Tầng", false, 20, "1", ""),
                text("zone", "Khu", false, 50, "Khu A", ""),
                typed("capacity", "Sức chứa", false, ColumnType.INTEGER, "200", "Từ 1 đến 100000")));
    }

    public static ImportSchema titles() {
        SheetSpec titles = new SheetSpec(SHEET_TITLES, false, List.of(
                text("isbn", "ISBN", false, 0, "9786041234567",
                        "Khóa chống trùng; để trống thì đầu sách không chống trùng được và không gắn được bản sao"),
                text("name", "Tên sách", true, 0, "Mắt biếc", ""),
                text("author", "Tác giả (hiển thị)", true, 0, "Nguyễn Nhật Ánh", "Chuỗi hiển thị trên đầu sách"),
                typed("coverPrice", "Giá bìa (VND)", true, ColumnType.MONEY, "95000", "Từ 0 trở lên"),
                new ColumnSpec("authors", "Tác giả chuẩn", false, ColumnType.LIST, 0, "Nguyễn Nhật Ánh",
                        "Tên các tác giả đã có trong thư viện, cách nhau bằng dấu ; (tối đa 20)", List.of()),
                text("publisher", "Nhà xuất bản", false, 0, "NXB Trẻ", "Tên nhà xuất bản đã có và đang hoạt động"),
                text("category", "Thể loại", false, 0, "Văn học", "Tên thể loại đã có và đang hoạt động"),
                text("subtitle", "Phụ đề", false, 255, "", ""),
                typed("publicationYear", "Năm xuất bản", false, ColumnType.INTEGER, "2019", "Từ 1000 đến năm hiện tại + 1"),
                text("edition", "Lần tái bản", false, 50, "Tái bản lần 5", ""),
                text("language", "Ngôn ngữ", false, 8, "vi", "2-8 chữ cái thường (vi, en...)"),
                typed("pageCount", "Số trang", false, ColumnType.INTEGER, "300", "Từ 1 đến 20000"),
                ColumnSpec.enumeration("format", "Định dạng", false, List.of("PAPERBACK", "HARDCOVER", "EBOOK")),
                text("translator", "Dịch giả", false, 255, "", ""),
                text("series", "Bộ sách", false, 255, "", ""),
                typed("seriesNumber", "Tập", false, ColumnType.INTEGER, "", "Lớn hơn 0, chỉ khi có bộ sách"),
                text("coverImageUrl", "Ảnh bìa (URL)", false, 500, "", "URL http hoặc https"),
                new ColumnSpec("tags", "Nhãn", false, ColumnType.LIST, 0, "tiểu thuyết;tuổi trẻ",
                        "Cách nhau bằng dấu ; (tối đa 10 nhãn, mỗi nhãn tối đa 50 ký tự)", List.of()),
                text("description", "Mô tả", false, 0, "", "Tối đa 500 ký tự")));
        SheetSpec copies = new SheetSpec(SHEET_COPIES, false, List.of(
                text("isbn", "ISBN đầu sách", true, 0, "9786041234567",
                        "ISBN của đầu sách đã có hoặc của một dòng ở sheet DauSach trong cùng file"),
                text("barcode", "Mã vạch", true, 64, "BC000001", "Duy nhất trong thư viện"),
                text("shelfLocationCode", "Mã vị trí kệ", false, 0, "A1-01", "Mã vị trí kệ đã có và đang hoạt động"),
                typed("acquiredDate", "Ngày nhập", false, ColumnType.DATE, "2024-01-15", "Không ở tương lai"),
                ColumnSpec.enumeration("acquisitionSource", "Nguồn nhập", false,
                        List.of("PURCHASE", "DONATION", "EXCHANGE", "OTHER")),
                typed("purchasePrice", "Giá mua (VND)", false, ColumnType.INTEGER, "",
                        "Số nguyên >= 0, chỉ khi nguồn nhập là PURCHASE"),
                ColumnSpec.enumeration("conditionGrade", "Tình trạng", false, List.of("NEW", "GOOD", "FAIR", "WORN")),
                text("conditionNote", "Ghi chú tình trạng", false, 300, "", ""),
                typed("lastInventoryDate", "Ngày kiểm kê", false, ColumnType.DATE, "",
                        "Không ở tương lai, không trước ngày nhập")));
        return new ImportSchema(TITLES, 1, List.of(titles, copies));
    }
}
