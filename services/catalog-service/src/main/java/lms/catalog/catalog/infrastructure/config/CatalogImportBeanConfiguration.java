package lms.catalog.catalog.infrastructure.config;

import lms.cache.RequestCache;
import lms.catalog.catalog.application.imports.CatalogImportSchemas;
import lms.catalog.catalog.application.imports.ImportAuditPort;
import lms.catalog.catalog.application.imports.ReferenceCachePort;
import lms.catalog.catalog.application.imports.ReferenceChunkWriter;
import lms.catalog.catalog.application.imports.ReferenceImportHandler;
import lms.catalog.catalog.application.imports.TitleChunkWriter;
import lms.catalog.catalog.application.imports.TitleImportHandler;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.catalog.infrastructure.audit.LoggingImportAudit;
import lms.catalog.catalog.infrastructure.cache.RequestCacheReferenceCachePort;
import lms.catalog.inventory.application.RegisterCopiesInBatchUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của nhập Excel (ADR-0024) cho 6 loại catalog (thêm series, ADR-0026): handler POJO, writer giao dịch, cổng cache/audit. */
@Configuration
public class CatalogImportBeanConfiguration {

    @Bean
    ImportAuditPort importAuditPort() {
        return new LoggingImportAudit();
    }

    @Bean
    ReferenceCachePort referenceCachePort(RequestCache cache) {
        return new RequestCacheReferenceCachePort(cache);
    }

    @Bean
    ReferenceChunkWriter referenceChunkWriter(ReferenceRepository references) {
        return new ReferenceChunkWriter(references);
    }

    @Bean
    TitleChunkWriter titleChunkWriter(TitleRepository titles, WorkRepository works, ReferenceRepository references,
                                      RegisterCopiesInBatchUseCase copies) {
        return new TitleChunkWriter(titles, works, references, copies);
    }

    @Bean
    TitleImportHandler titleImportHandler(TitleRepository titles, WorkRepository works, ReferenceRepository references,
                                          RegisterCopiesInBatchUseCase copies, TitleChunkWriter writer,
                                          ImportAuditPort audit) {
        return new TitleImportHandler(titles, works, references, copies, writer, audit);
    }

    @Bean
    ReferenceImportHandler authorImportHandler(ReferenceRepository references, ReferenceChunkWriter writer,
                                               ReferenceCachePort cache, ImportAuditPort audit) {
        return new ReferenceImportHandler(ReferenceKind.AUTHOR, CatalogImportSchemas.authors(), references, writer,
                cache, audit);
    }

    @Bean
    ReferenceImportHandler publisherImportHandler(ReferenceRepository references, ReferenceChunkWriter writer,
                                                  ReferenceCachePort cache, ImportAuditPort audit) {
        return new ReferenceImportHandler(ReferenceKind.PUBLISHER, CatalogImportSchemas.publishers(), references,
                writer, cache, audit);
    }

    @Bean
    ReferenceImportHandler categoryImportHandler(ReferenceRepository references, ReferenceChunkWriter writer,
                                                 ReferenceCachePort cache, ImportAuditPort audit) {
        return new ReferenceImportHandler(ReferenceKind.CATEGORY, CatalogImportSchemas.categories(), references,
                writer, cache, audit);
    }

    @Bean
    ReferenceImportHandler shelfLocationImportHandler(ReferenceRepository references, ReferenceChunkWriter writer,
                                                      ReferenceCachePort cache, ImportAuditPort audit) {
        return new ReferenceImportHandler(ReferenceKind.SHELF_LOCATION, CatalogImportSchemas.shelfLocations(),
                references, writer, cache, audit);
    }

    @Bean
    ReferenceImportHandler seriesImportHandler(ReferenceRepository references, ReferenceChunkWriter writer,
                                               ReferenceCachePort cache, ImportAuditPort audit) {
        return new ReferenceImportHandler(ReferenceKind.SERIES, CatalogImportSchemas.series(), references, writer,
                cache, audit);
    }
}
