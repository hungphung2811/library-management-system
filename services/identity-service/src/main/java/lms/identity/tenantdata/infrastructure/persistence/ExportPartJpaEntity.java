package lms.identity.tenantdata.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lms.identity.tenantdata.domain.ExportPart;
import lms.identity.tenantdata.domain.ExportedTable;
import lms.identity.tenantdata.domain.PartStatus;

/** Tiến độ một nguồn của yêu cầu xuất; bảng con của tenant_data_exports (không có cột tenant_id). */
@Entity
@Table(name = "tenant_data_export_parts")
@IdClass(ExportPartJpaEntity.Key.class)
class ExportPartJpaEntity {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ExportedTable>> TABLES = new TypeReference<>() { };

    static class Key implements Serializable {

        private UUID exportId;
        private String service;

        protected Key() {
        }

        Key(UUID exportId, String service) {
            this.exportId = exportId;
            this.service = service;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(exportId, k.exportId) && Objects.equals(service, k.service);
        }

        @Override
        public int hashCode() {
            return Objects.hash(exportId, service);
        }
    }

    @Id
    @Column(name = "export_id")
    private UUID exportId;

    @Id
    @Column(name = "service")
    private String service;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "tables_json", nullable = false)
    private String tablesJson;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ExportPartJpaEntity() {
    }

    static ExportPartJpaEntity from(ExportPart p) {
        ExportPartJpaEntity j = new ExportPartJpaEntity();
        j.exportId = p.exportId();
        j.service = p.service();
        j.apply(p);
        return j;
    }

    void apply(ExportPart p) {
        this.status = p.status().name();
        this.attempts = p.attempts();
        try {
            this.tablesJson = JSON.writeValueAsString(p.tables());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        this.errorCode = p.errorCode();
        this.finishedAt = p.finishedAt();
    }

    ExportPart toDomain() {
        List<ExportedTable> tables;
        try {
            tables = tablesJson == null || tablesJson.isBlank() ? List.of() : JSON.readValue(tablesJson, TABLES);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            tables = List.of();
        }
        return ExportPart.restore(exportId, service, PartStatus.valueOf(status), attempts, tables, errorCode,
                finishedAt);
    }

    UUID getExportId() { return exportId; }
}
