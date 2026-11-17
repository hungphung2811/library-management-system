package lms.identity.tenant.application;

import java.util.List;

/** Danh sách origin của một tenant cho platform admin, kèm giới hạn tối đa. */
public record StorefrontOriginListResponse(int maxOrigins, List<StorefrontOriginResponse> origins) {
}
