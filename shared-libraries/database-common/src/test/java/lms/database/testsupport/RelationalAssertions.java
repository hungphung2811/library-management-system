package lms.database.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.database.api.HealthStatus;
import lms.database.api.RelationalConnection;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Bộ kiểm tra dùng chung cho mọi provider quan hệ: health, ghi/đọc qua JPA, commit, rollback. */
public final class RelationalAssertions {

    private RelationalAssertions() {
    }

    public static void verifyRoundTripAndRollback(RelationalConnection connection) {
        assertThat(connection.health()).extracting(HealthStatus::status).isEqualTo(HealthStatus.Status.UP);
        EntityManagerFactory emf = connection.entityManagerFactory().orElseThrow();
        TransactionTemplate tx = new TransactionTemplate(connection.transactionManager());
        JdbcTemplate jdbc = new JdbcTemplate(connection.dataSource());

        // Commit: dữ liệu ghi bằng JPA đọc lại được bằng JDBC thuần trên cùng DataSource.
        tx.executeWithoutResult(status -> em(emf).persist(new Note("n1", "đã commit")));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isEqualTo(1);

        // Rollback: ném exception sau khi ghi thì dữ liệu không được giữ lại.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            em(emf).persist(new Note("n2", "sẽ bị rollback"));
            throw new IllegalStateException("lỗi nghiệp vụ");
        })).hasMessage("lỗi nghiệp vụ");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notes WHERE id = 'n2'", Integer.class)).isZero();
    }

    /** EntityManager gắn với transaction hiện tại của TransactionManager. */
    private static EntityManager em(EntityManagerFactory emf) {
        return org.springframework.orm.jpa.EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
    }
}
