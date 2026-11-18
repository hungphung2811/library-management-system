package lms.imports.web;

import lms.imports.engine.ImportActor;

/** Xác định người gọi hiện tại (tenant, userId, module). Mặc định đọc từ JWT; test có thể thay bằng bean riêng. */
public interface ImportActorResolver {

    /** Ném {@code TenantRequiredException} khi không có tenant (403 TENANT_REQUIRED như hiện tại). */
    ImportActor current();
}
