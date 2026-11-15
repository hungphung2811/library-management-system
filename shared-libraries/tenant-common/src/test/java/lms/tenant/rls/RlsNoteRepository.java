package lms.tenant.rls;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RlsNoteRepository extends JpaRepository<RlsNote, UUID> {
}
