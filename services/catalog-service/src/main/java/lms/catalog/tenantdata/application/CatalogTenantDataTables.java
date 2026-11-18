package lms.catalog.tenantdata.application;

import java.util.List;
import lms.tenant.export.ExportExclusion;
import lms.tenant.export.ExportTable;

/**
 * Danh sách bảng/cột catalog xuất cho tenant (ADR-0031 F1), cố định trong mã, khớp migration
 * {@code system-service/.../db/migration/catalog} đến V11. Bảng mới có {@code tenant_id} phải được thêm vào đây hoặc vào
 * {@link #EXCLUDED} kèm lý do (test {@code CatalogTenantDataTablesTest} so với schema thật ở IT).
 * {@code tenant_id} không xuất (CSV chỉ của một tenant). Không có cột bí mật trong catalog.
 */
public final class CatalogTenantDataTables {

    private CatalogTenantDataTables() {
    }

    public static final String SERVICE = "catalog";

    public static final List<ExportTable> TABLES = List.of(
            ExportTable.of("categories", List.of("id"),
                    "id", "parent_id", "name", "name_key", "description", "active", "created_at"),
            ExportTable.of("authors", List.of("id"),
                    "id", "name", "name_key", "description", "active", "created_at", "birth_year", "death_year",
                    "nationality", "photo_url", "website"),
            ExportTable.of("publishers", List.of("id"),
                    "id", "name", "name_key", "description", "active", "created_at", "country", "founded_year",
                    "website", "address"),
            ExportTable.of("shelf_locations", List.of("id"),
                    "id", "code", "code_key", "description", "active", "created_at", "floor", "zone", "capacity"),
            ExportTable.of("series", List.of("id"),
                    "id", "name", "name_key", "description", "active", "created_at"),
            ExportTable.of("works", List.of("id"),
                    "id", "title", "original_title", "original_language", "author", "description", "category_id",
                    "series_id", "series_number", "series_label", "kind", "created_at"),
            ExportTable.of("work_authors", List.of("work_id", "author_id"),
                    "work_id", "author_id", "sort_order"),
            ExportTable.of("titles", List.of("id"),
                    "id", "name", "author", "isbn", "cover_price", "created_at", "description", "preview_text",
                    "publisher_id", "category_id", "subtitle", "publication_year", "edition", "language",
                    "page_count", "format", "translator", "series", "series_number", "cover_image_url", "tags",
                    "work_id", "part_number", "part_label"),
            ExportTable.of("title_authors", List.of("title_id", "author_id"),
                    "title_id", "author_id", "sort_order"),
            ExportTable.of("book_copies", List.of("id"),
                    "id", "title_id", "barcode", "status", "created_at", "shelf_location_id", "acquired_date",
                    "acquisition_source", "purchase_price", "condition_grade", "condition_note",
                    "last_inventory_date"));

    public static final List<ExportExclusion> EXCLUDED = List.of();
}
