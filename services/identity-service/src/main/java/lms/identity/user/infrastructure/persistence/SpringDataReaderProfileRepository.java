package lms.identity.user.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataReaderProfileRepository extends JpaRepository<ReaderProfileJpaEntity, UUID> {

    boolean existsByCardNo(String cardNo);

    @Query("select p.cardNo from ReaderProfileJpaEntity p where p.cardNo in :cardNos")
    List<String> findCardNosIn(@Param("cardNos") java.util.Collection<String> cardNos);

    @Query("select p.userId from ReaderProfileJpaEntity p where lower(p.email) = lower(:email)")
    List<UUID> findUserIdsByEmail(@Param("email") String email);
}
