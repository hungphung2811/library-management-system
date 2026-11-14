package lms.database.core;

import lms.database.api.ConnectionSpec;
import lms.database.api.ConnectionState;
import lms.database.api.DatabaseConnection;

/** Một mục trong registry: connection đang mở, hoặc bản ghi trạng thái DISABLED/FAILED (connection = null). */
public record RegisteredConnection(String name, ConnectionSpec spec, ConnectionState state,
                                   DatabaseConnection connection, String detail) {
}
