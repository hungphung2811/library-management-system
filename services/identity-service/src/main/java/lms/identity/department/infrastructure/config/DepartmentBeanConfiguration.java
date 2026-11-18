package lms.identity.department.infrastructure.config;

import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.cache.RequestCache;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.infrastructure.cache.CachedDepartmentUseCases;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context department. */
@Configuration
public class DepartmentBeanConfiguration {

    @Bean
    CreateDepartmentUseCase createDepartmentUseCase(DepartmentRepository repository, RequestCache cache) {
        return new CachedDepartmentUseCases.EvictingCreate(repository, cache);
    }

    @Bean
    UpdateDepartmentUseCase updateDepartmentUseCase(DepartmentRepository repository, RequestCache cache) {
        return new CachedDepartmentUseCases.EvictingUpdate(repository, cache);
    }

    @Bean
    ChangeDepartmentStatusUseCase changeDepartmentStatusUseCase(DepartmentRepository repository, RequestCache cache) {
        return new CachedDepartmentUseCases.EvictingChangeStatus(repository, cache);
    }

    @Bean
    GetDepartmentUseCase getDepartmentUseCase(DepartmentRepository repository) {
        return new GetDepartmentUseCase(repository);
    }

    @Bean
    ListDepartmentsUseCase listDepartmentsUseCase(DepartmentRepository repository, RequestCache cache) {
        return new CachedDepartmentUseCases.CachedList(repository, cache);
    }
}
