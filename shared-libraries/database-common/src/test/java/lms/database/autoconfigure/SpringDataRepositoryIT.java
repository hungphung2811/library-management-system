package lms.database.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.HealthStatus;
import lms.database.core.DatabaseConnectionRegistry;
import lms.database.health.DatabaseHealthChecker;
import lms.database.testsupport.Note;
import lms.database.testsupport.NoteRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Kiểm chứng cách service dùng thư viện: Spring Data repository gắn với EntityManagerFactory lấy từ registry
 * (qua bean có tên theo quy ước) và @Transactional("<name>TransactionManager") chọn đúng TransactionManager.
 */
@Tag("docker")
@Testcontainers
class SpringDataRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = NoteRepository.class,
            entityManagerFactoryRef = "mainDbEntityManagerFactory", transactionManagerRef = "mainDbTransactionManager")
    static class ServiceConfig {
        @Bean
        NoteService noteService(NoteRepository repository) {
            return new NoteService(repository);
        }
    }

    public static class NoteService {
        private final NoteRepository repository;

        NoteService(NoteRepository repository) {
            this.repository = repository;
        }

        @Transactional("mainDbTransactionManager")
        public void save(String id, String content) {
            repository.save(new Note(id, content));
        }

        @Transactional("mainDbTransactionManager")
        public void saveThenFail(String id) {
            repository.save(new Note(id, "sẽ bị rollback"));
            throw new IllegalStateException("lỗi nghiệp vụ");
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DatabaseAutoConfiguration.class))
                .withUserConfiguration(ServiceConfig.class)
                .withPropertyValues(
                        "database.connections.MAIN_DB.type=POSTGRESQL",
                        "database.connections.MAIN_DB.url=" + postgres.getJdbcUrl(),
                        "database.connections.MAIN_DB.username=" + postgres.getUsername(),
                        "database.connections.MAIN_DB.password=" + postgres.getPassword(),
                        "database.connections.MAIN_DB.entity-packages=" + Note.class.getPackageName(),
                        "database.connections.MAIN_DB.jpa-properties.hibernate.hbm2ddl.auto=create-drop");
    }

    @Test
    void repositoryDungEmfTuRegistryVaTransactionalChonDungManager() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            NoteRepository repository = context.getBean(NoteRepository.class);
            NoteService service = context.getBean(NoteService.class);

            service.save("n1", "đã commit");
            assertThatThrownBy(() -> service.saveThenFail("n2")).hasMessage("lỗi nghiệp vụ");

            assertThat(repository.findAll()).extracting(Note::getId).containsExactly("n1");
            assertThat(context.getBean("mainDbTransactionManager"))
                    .isSameAs(context.getBean(DatabaseConnectionRegistry.class)
                            .require("MAIN_DB", lms.database.api.RelationalConnection.class).transactionManager());
            assertThat(context.getBean(DatabaseHealthChecker.class).check("MAIN_DB").status())
                    .isEqualTo(HealthStatus.Status.UP);
        });
    }

    @Test
    void dongContextThiDongPool() {
        DatabaseConnectionRegistry[] holder = new DatabaseConnectionRegistry[1];
        runner().run(context -> holder[0] = context.getBean(DatabaseConnectionRegistry.class));

        assertThat(holder[0].names()).isEmpty(); // lifecycle đã đóng và gỡ mọi connection
    }
}
