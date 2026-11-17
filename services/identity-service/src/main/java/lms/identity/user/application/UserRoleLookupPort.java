package lms.identity.user.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cổng sang context access-control: tên role (trong tenant hiện tại) của nhiều người dùng, tra theo lô. */
public interface UserRoleLookupPort {
    Map<UUID, List<String>> roleNames(Collection<UUID> userIds);
}
