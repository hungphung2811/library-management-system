package lms.identity.apiclient.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Khóa chính kép (application_id, scope) của application_scopes. */
class ApplicationScopeId implements Serializable {

    private UUID applicationId;
    private String scope;

    protected ApplicationScopeId() {
    }

    ApplicationScopeId(UUID applicationId, String scope) {
        this.applicationId = applicationId;
        this.scope = scope;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ApplicationScopeId other
                && Objects.equals(applicationId, other.applicationId) && Objects.equals(scope, other.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(applicationId, scope);
    }
}
