package dev.untangle.assignment.infrastructure;

import dev.untangle.assignment.domain.AutoAssignSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutoAssignSettingsRepository extends JpaRepository<AutoAssignSettings, UUID> {

  /** The single settings row, if anyone has saved settings yet. */
  Optional<AutoAssignSettings> findFirstByOrderByCreatedAtAsc();
}
