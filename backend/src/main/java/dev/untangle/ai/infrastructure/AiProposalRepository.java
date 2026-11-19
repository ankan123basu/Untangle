package dev.untangle.ai.infrastructure;

import dev.untangle.ai.domain.AiProposal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProposalRepository extends JpaRepository<AiProposal, UUID> {

  Optional<AiProposal> findFirstByTicketIdOrderByCreatedAtDesc(UUID ticketId);

  List<AiProposal> findByTicketIdAndStatus(UUID ticketId, AiProposal.Status status);
}
