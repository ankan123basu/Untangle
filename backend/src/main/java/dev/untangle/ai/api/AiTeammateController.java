package dev.untangle.ai.api;

import dev.untangle.ai.application.AiTeammate;
import dev.untangle.ai.application.AiTeammate.ProposalEnvelope;
import dev.untangle.ai.application.AiTeammate.ProposalView;
import dev.untangle.ai.application.AiTeammate.TeammateState;
import dev.untangle.security.AuthenticatedAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI teammate (ADR-028). It works tickets assigned to it and proposes a resolution; only a lead
 * can approve (resolve) or take the ticket over. There is no endpoint that lets the AI act alone.
 */
@Tag(name = "AI teammate")
@RestController
public class AiTeammateController {

  public record Decision(@NotNull Long version) {}

  private final AiTeammate teammate;

  public AiTeammateController(AiTeammate teammate) {
    this.teammate = teammate;
  }

  @Operation(summary = "Who the AI teammate is and whether it can work right now")
  @GetMapping("/api/v1/ai/teammate")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public TeammateState state() {
    return teammate.state();
  }

  @Operation(summary = "The AI teammate's latest proposal for this ticket (null when none)")
  @GetMapping("/api/v1/tickets/{id}/ai-proposal")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public ProposalEnvelope proposal(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedAgent me) {
    return teammate.current(id, me);
  }

  @Operation(summary = "Approve the proposal: the ticket is resolved with its note (leads)")
  @PostMapping("/api/v1/tickets/{id}/ai-proposal/approve")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public ProposalView approve(
      @PathVariable UUID id,
      @Valid @RequestBody Decision decision,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return teammate.approve(id, decision.version(), me);
  }

  @Operation(summary = "Take the ticket over instead (leads): assigned to you, proposal not used")
  @PostMapping("/api/v1/tickets/{id}/ai-proposal/take-over")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public ProposalView takeOver(
      @PathVariable UUID id,
      @Valid @RequestBody Decision decision,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return teammate.takeOver(id, decision.version(), me);
  }
}
