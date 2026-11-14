package lms.identity.accesscontrol.domain;

import java.util.UUID;

public record RoleId(UUID value) {

    public RoleId {
        if (value == null) throw new IllegalArgumentException("RoleId không được null");
    }

    public static RoleId newId() {
        return new RoleId(UUID.randomUUID());
    }
}
