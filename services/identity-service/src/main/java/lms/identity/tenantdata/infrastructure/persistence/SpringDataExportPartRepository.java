package lms.identity.tenantdata.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataExportPartRepository extends JpaRepository<ExportPartJpaEntity, ExportPartJpaEntity.Key> {

    @Query("select p from ExportPartJpaEntity p where p.exportId = :id order by p.service")
    List<ExportPartJpaEntity> findByExport(@Param("id") UUID exportId);

    @Query("select p from ExportPartJpaEntity p where p.exportId in :ids order by p.exportId, p.service")
    List<ExportPartJpaEntity> findByExports(@Param("ids") Collection<UUID> exportIds);

    @Modifying
    @Query("delete from ExportPartJpaEntity p where p.exportId = :id")
    void deleteByExport(@Param("id") UUID exportId);
}
