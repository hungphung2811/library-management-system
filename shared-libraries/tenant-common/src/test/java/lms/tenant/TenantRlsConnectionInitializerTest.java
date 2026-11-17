package lms.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Câu SQL và tham số đặt biến phiên RLS cho từng phạm vi (ADR-0008). */
class TenantRlsConnectionInitializerTest {

    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);

    @BeforeEach
    void setUp() throws Exception {
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(connection.getMetaData()).thenReturn(meta);
        when(connection.isClosed()).thenReturn(false);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(TenantRlsConnectionInitializer.SET_SQL)).thenReturn(statement);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void verifySet(String tenant, String scope) throws Exception {
        InOrder order = inOrder(statement);
        order.verify(statement).setString(1, tenant);
        order.verify(statement).setString(2, scope);
        order.verify(statement).execute();
    }

    @Test
    void cauSqlDatCaHaiBienTrongMotCauLenh() {
        assertThat(TenantRlsConnectionInitializer.SET_SQL).isEqualTo(
                "SELECT set_config('app.tenant_id', ?, false), set_config('app.tenant_scope', ?, false)");
    }

    @Test
    void motTenantDatTenantVaScopeRong() throws Exception {
        TenantContext.set(A);

        TenantRlsConnectionInitializer.apply(connection);

        verifySet(A.toString(), "");
    }

    @Test
    void phamViAllDatTenantRongVaScopeAll() throws Exception {
        TenantContext.bindAllTenants();

        TenantRlsConnectionInitializer.apply(connection);

        verifySet("", "all");
    }

    @Test
    void khongCoTenantDatCaHaiRong() throws Exception {
        TenantRlsConnectionInitializer.apply(connection);

        verifySet("", "");
    }

    @Test
    void resetDatCaHaiVeRongKeCaKhiDangOPhamViAll() throws Exception {
        TenantContext.bindAllTenants();

        TenantRlsConnectionInitializer.reset(connection);

        verifySet("", "");
    }

    @Test
    void ketNoiKhongPhaiPostgresBiBoQua() throws Exception {
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(meta.getDatabaseProductName()).thenReturn("Oracle");
        when(connection.getMetaData()).thenReturn(meta);
        TenantContext.bindAllTenants();

        TenantRlsConnectionInitializer.apply(connection);

        verify(connection, never()).prepareStatement(anyString());
    }
}
