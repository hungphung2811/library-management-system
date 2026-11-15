package lms.circulation.borrowing.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.ItemCondition;
import lms.circulation.borrowing.infrastructure.config.PersistenceConfiguration;
import lms.circulation.borrowing.infrastructure.persistence.BorrowingPersistenceAdapter;
import lms.circulation.integration.AppRoleDatabase;
import lms.tenant.TenantAutoConfiguration;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy: mvn test -Dtest=BorrowingPersistenceAdapterIT. Ứng dụng kết nối bằng circulation_app (chịu RLS).
 * Mọi test chạy trong tenant A; tenant đặt ở @BeforeAll vì transaction của test mở trước @BeforeEach, mà tenant của
 * phiên Hibernate và biến RLS chốt khi transaction bắt đầu. Dữ liệu tenant khác seed bằng superuser.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(TenantAutoConfiguration.class)
@Testcontainers
@Import(PersistenceConfiguration.class)
class BorrowingPersistenceAdapterIT {

    static final UUID TENANT_A = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @BeforeAll
    static void vaoTenantA() {
        TenantContext.set(TENANT_A);
    }

    @AfterAll
    static void roiTenant() {
        TenantContext.clear();
    }

    @Autowired
    BorrowingPersistenceAdapter adapter;

    @Autowired
    EntityManager entityManager;

    @Test
    void luuRoiDocLaiVaDemSoBanSaoDangMuon() {
        UUID userId = UUID.randomUUID();
        UUID copy1 = UUID.randomUUID();
        UUID copy2 = UUID.randomUUID();
        BorrowingRecord record = BorrowingRecord.borrow(userId, List.of(copy1, copy2), LocalDate.of(2026, 1, 1));

        adapter.save(record);
        assertThat(adapter.countActiveItemsByUserId(userId)).isEqualTo(2);

        BorrowingRecord loaded = adapter.findById(record.id()).orElseThrow();
        loaded.returnCopy(copy1, LocalDate.of(2026, 1, 5));
        adapter.save(loaded);

        assertThat(adapter.countActiveItemsByUserId(userId)).isEqualTo(1);
        // item_condition được ghi cùng return_date (ck_borrowing_items_closed của V4)
        BorrowingRecord reloaded = adapter.findById(record.id()).orElseThrow();
        assertThat(reloaded.items()).filteredOn(i -> i.bookCopyId().equals(copy1)).singleElement()
                .satisfies(i -> assertThat(i.condition()).isEqualTo(ItemCondition.GOOD));
        assertThat(reloaded.items()).filteredOn(i -> i.bookCopyId().equals(copy2)).singleElement()
                .satisfies(i -> assertThat(i.condition()).isNull());
    }

    @Test
    void phieuMuonVaBanSaoGhiTenantHienTai() {
        BorrowingRecord record = BorrowingRecord.borrow(UUID.randomUUID(), List.of(UUID.randomUUID()), LocalDate.now());
        adapter.save(record);
        entityManager.flush();

        Object borrowingTenant = entityManager.createNativeQuery(
                "SELECT tenant_id FROM borrowings WHERE id = '" + record.id().value() + "'").getSingleResult();
        Object itemTenant = entityManager.createNativeQuery(
                "SELECT tenant_id FROM borrowing_items WHERE borrowing_id = '" + record.id().value() + "'")
                .getSingleResult();
        assertThat(borrowingTenant.toString()).isEqualTo(TENANT_A.toString());
        assertThat(itemTenant.toString()).isEqualTo(TENANT_A.toString());
    }

    @Test
    void phieuMuonCuaTenantKhacKhongThayKeCaQuaSqlNativeVaKhongTinhVaoHanMuc() {
        UUID tenantB = UUID.randomUUID();
        UUID borrowingB = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        AppRoleDatabase.asSuperuser(postgres, "INSERT INTO borrowings (id, tenant_id, user_id, borrow_date, due_date) "
                + "VALUES ('" + borrowingB + "', '" + tenantB + "', '" + user + "', DATE '2026-01-01', DATE '2026-01-15')");
        AppRoleDatabase.asSuperuser(postgres, "INSERT INTO borrowing_items (borrowing_id, tenant_id, book_copy_id) VALUES ('"
                + borrowingB + "', '" + tenantB + "', '" + copy + "')");

        assertThat(adapter.findById(new lms.circulation.borrowing.domain.BorrowingId(borrowingB))).isEmpty();
        assertThat(adapter.countActiveItemsByUserId(user)).isZero();
        Number nativeCount = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM borrowing_items WHERE borrowing_id = '" + borrowingB + "'").getSingleResult();
        assertThat(nativeCount.intValue()).isZero();

        // cùng bản sao (UUID) đang mượn ở tenant B vẫn mượn được ở tenant A: unique một phần tính theo tenant
        BorrowingRecord inA = BorrowingRecord.borrow(user, List.of(copy), LocalDate.now());
        adapter.save(inA);
        entityManager.flush();
        assertThat(adapter.countActiveItemsByUserId(user)).isEqualTo(1);
    }
}
