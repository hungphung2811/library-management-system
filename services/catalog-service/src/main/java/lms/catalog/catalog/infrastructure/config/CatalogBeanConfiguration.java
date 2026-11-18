package lms.catalog.catalog.infrastructure.config;

import lms.cache.RequestCache;
import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.CreateWorkUseCase;
import lms.catalog.catalog.application.GetWorkUseCase;
import lms.catalog.catalog.application.MergeWorksUseCase;
import lms.catalog.catalog.application.MoveEditionToWorkUseCase;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SearchWorksUseCase;
import lms.catalog.catalog.application.TitleClassificationService;
import lms.catalog.catalog.application.TitleResponseAssembler;
import lms.catalog.catalog.application.UpdateWorkUseCase;
import lms.catalog.catalog.application.WorkService;
import lms.catalog.catalog.application.imports.ReferenceCachePort;
import lms.catalog.catalog.infrastructure.cache.CachedReferenceUseCases;
import lms.catalog.catalog.domain.ReferenceRepository;
import lms.catalog.catalog.application.GetTitlePreviewUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.LookupTitlesByKeywordUseCase;
import lms.catalog.catalog.application.UpdateTitlePreviewUseCase;
import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.UpdateTitleUseCase;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composition root của context catalog. Tác phẩm (works) và ấn bản (/books) KHÔNG cache (kèm số bản sao sẵn có, ADR-0026
 * mục 3.10); chỉ danh mục tham chiếu (kể cả bộ sách) cache qua {@link CachedReferenceUseCases}.
 */
@Configuration
public class CatalogBeanConfiguration {

    @Bean
    TitleClassificationService titleClassificationService(ReferenceRepository references) {
        return new TitleClassificationService(references);
    }

    @Bean
    ReferenceUseCases referenceUseCases(ReferenceRepository references, RequestCache cache) {
        // Danh mục tham chiếu đọc qua cache (TTL 60s, theo tenant); ghi xóa namespace của loại tương ứng
        return CachedReferenceUseCases.create(references, cache);
    }

    @Bean
    WorkService workService(WorkRepository works, TitleRepository titles, ReferenceRepository references,
                            CopyAvailabilityQuery availability, ReferenceCachePort referenceCache) {
        return new WorkService(works, titles, references, availability, referenceCache);
    }

    @Bean
    TitleResponseAssembler titleResponseAssembler(CopyAvailabilityQuery availability,
                                                  TitleClassificationService classification, WorkService workService) {
        return new TitleResponseAssembler(availability, classification, workService);
    }

    @Bean
    CreateTitleUseCase createTitleUseCase(TitleRepository repository, TitleClassificationService classification,
                                          WorkRepository works, WorkService workService,
                                          TitleResponseAssembler assembler) {
        return new CreateTitleUseCase(repository, classification, works, workService, assembler);
    }

    @Bean
    UpdateTitleUseCase updateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                                          TitleClassificationService classification, WorkRepository works,
                                          WorkService workService, TitleResponseAssembler assembler) {
        return new UpdateTitleUseCase(repository, availability, classification, works, workService, assembler);
    }

    @Bean
    GetTitleUseCase getTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                                    TitleClassificationService classification, TitleResponseAssembler assembler) {
        return new GetTitleUseCase(repository, availability, classification, assembler);
    }

    @Bean
    lms.catalog.catalog.application.LookupTitlesUseCase lookupTitlesUseCase(TitleRepository repository,
                                                                            WorkService workService) {
        return new lms.catalog.catalog.application.LookupTitlesUseCase(repository, workService);
    }

    @Bean
    lms.catalog.catalog.application.LookupTitlesByIsbnUseCase lookupTitlesByIsbnUseCase(TitleRepository repository,
                                                                                        WorkService workService) {
        return new lms.catalog.catalog.application.LookupTitlesByIsbnUseCase(repository, workService);
    }

    @Bean
    GetTitlePreviewUseCase getTitlePreviewUseCase(TitleRepository repository) {
        return new GetTitlePreviewUseCase(repository);
    }

    @Bean
    UpdateTitlePreviewUseCase updateTitlePreviewUseCase(TitleRepository repository) {
        return new UpdateTitlePreviewUseCase(repository);
    }

    @Bean
    LookupTitlesByKeywordUseCase lookupTitlesByKeywordUseCase(TitleRepository repository, WorkService workService) {
        return new LookupTitlesByKeywordUseCase(repository, workService);
    }

    @Bean
    lms.catalog.catalog.application.ListNewAuthorsUseCase listNewAuthorsUseCase(
            lms.catalog.catalog.application.NewAuthorsQuery query) {
        return new lms.catalog.catalog.application.ListNewAuthorsUseCase(query, java.time.Clock.systemUTC());
    }

    @Bean
    SearchTitlesUseCase searchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                                            TitleResponseAssembler assembler, WorkRepository works) {
        return new SearchTitlesUseCase(repository, availability, assembler, works);
    }

    @Bean
    CreateWorkUseCase createWorkUseCase(WorkRepository works, WorkService workService,
                                        TitleClassificationService classification) {
        return new CreateWorkUseCase(works, workService, classification);
    }

    @Bean
    UpdateWorkUseCase updateWorkUseCase(WorkRepository works, WorkService workService,
                                        TitleClassificationService classification) {
        return new UpdateWorkUseCase(works, workService, classification);
    }

    @Bean
    GetWorkUseCase getWorkUseCase(WorkRepository works, WorkService workService) {
        return new GetWorkUseCase(works, workService);
    }

    @Bean
    SearchWorksUseCase searchWorksUseCase(WorkRepository works, WorkService workService) {
        return new SearchWorksUseCase(works, workService);
    }

    @Bean
    MergeWorksUseCase mergeWorksUseCase(WorkRepository works, TitleRepository titles, WorkService workService) {
        return new MergeWorksUseCase(works, titles, workService);
    }

    @Bean
    MoveEditionToWorkUseCase moveEditionToWorkUseCase(TitleRepository titles, WorkRepository works,
                                                      WorkService workService, CreateWorkUseCase createWork,
                                                      TitleResponseAssembler assembler) {
        return new MoveEditionToWorkUseCase(titles, works, workService, createWork, assembler);
    }
}
