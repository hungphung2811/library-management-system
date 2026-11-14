package lms.identity.accesscontrol.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "permissions")
class PermissionJpaEntity {

    @Id
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String source;

    private String service;

    @Column(name = "http_method")
    private String httpMethod;

    @Column(name = "path_pattern")
    private String pathPattern;

    @Column(name = "first_seen_at")
    private Instant firstSeenAt;

    protected PermissionJpaEntity() {
    }

    PermissionJpaEntity(String code, String description, String source, String service, String httpMethod,
                        String pathPattern, Instant firstSeenAt) {
        this.code = code;
        this.description = description;
        this.source = source;
        this.service = service;
        this.httpMethod = httpMethod;
        this.pathPattern = pathPattern;
        this.firstSeenAt = firstSeenAt;
    }

    String getCode() { return code; }
    String getDescription() { return description; }
    String getSource() { return source; }
    String getService() { return service; }
    String getHttpMethod() { return httpMethod; }
    String getPathPattern() { return pathPattern; }
    Instant getFirstSeenAt() { return firstSeenAt; }
}
