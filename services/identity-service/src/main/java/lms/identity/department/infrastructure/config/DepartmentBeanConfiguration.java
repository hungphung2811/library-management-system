package lms.identity.department.infrastructure.config;

import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.GetDepartmentUseCase;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.identity.department.domain.DepartmentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root của context department. */
@Configuration
public class DepartmentBeanConfiguration {

    @Bean
    CreateDepartmentUseCase createDepartmentUseCase(DepartmentRepository repository) {
        return new CreateDepartmentUseCase(repository);
    }

    @Bean
    UpdateDepartmentUseCase updateDepartmentUseCase(DepartmentRepository repository) {
        return new UpdateDepartmentUseCase(repository);
    }

    @Bean
    ChangeDepartmentStatusUseCase changeDepartmentStatusUseCase(DepartmentRepository repository) {
        return new ChangeDepartmentStatusUseCase(repository);
    }

    @Bean
    GetDepartmentUseCase getDepartmentUseCase(DepartmentRepository repository) {
        return new GetDepartmentUseCase(repository);
    }

    @Bean
    ListDepartmentsUseCase listDepartmentsUseCase(DepartmentRepository repository) {
        return new ListDepartmentsUseCase(repository);
    }
}
