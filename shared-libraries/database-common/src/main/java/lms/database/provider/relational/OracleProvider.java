package lms.database.provider.relational;

import lms.database.api.DatabaseType;

/** Oracle: URL dạng jdbc:oracle:thin:@//host:1521/service, kiểm tra bằng SELECT 1 FROM DUAL. */
public class OracleProvider extends RelationalProviderSupport {

    public OracleProvider() {
        super(DatabaseType.ORACLE, "oracle.jdbc.OracleDriver", "org.hibernate.dialect.OracleDialect",
                "SELECT 1 FROM DUAL");
    }
}
