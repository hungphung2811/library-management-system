package lms.database.api;

/** Loại database mà thư viện hỗ trợ. Mỗi loại có đúng một DatabaseProvider tương ứng. */
public enum DatabaseType {
    POSTGRESQL(true),
    ORACLE(true),
    MONGODB(false),
    REDIS(false);

    private final boolean relational;

    DatabaseType(boolean relational) {
        this.relational = relational;
    }

    public boolean isRelational() {
        return relational;
    }
}
