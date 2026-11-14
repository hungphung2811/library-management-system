package lms.database.provider.relational;

import lms.database.api.DatabaseType;

public class PostgresProvider extends RelationalProviderSupport {

    public PostgresProvider() {
        super(DatabaseType.POSTGRESQL, "org.postgresql.Driver", null, null);
    }
}
