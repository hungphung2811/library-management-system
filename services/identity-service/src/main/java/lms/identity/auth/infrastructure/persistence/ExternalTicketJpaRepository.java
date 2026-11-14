package lms.identity.auth.infrastructure.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExternalTicketJpaRepository extends JpaRepository<ExternalTicketJpaEntity, String> {

    /** Đánh dấu đã dùng nguyên tử: chỉ một lời gọi thắng khi hai request đổi cùng một mã đồng thời. */
    @Modifying
    @Query("update ExternalTicketJpaEntity t set t.used = true where t.codeHash = :hash and t.used = false and t.expiresAt > :now")
    int markUsed(@Param("hash") String hash, @Param("now") Instant now);
}
