package dev.untangle.ai.api;

import dev.untangle.ai.application.AiAssistant;
import dev.untangle.ai.application.AiAssistant.ReplyDraft;
import dev.untangle.ai.application.AiAssistant.Status;
import dev.untangle.ai.application.AiAssistant.TimelineSummary;
import dev.untangle.ai.application.AiAssistant.TriageSuggestion;
import dev.untangle.security.AuthenticatedAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Optional AI helpers (§10). Every endpoint answers 503 AI_UNAVAILABLE when AI is off. */
@Tag(name = "AI assistance")
@RestController
public class AiController {

  public record TriageRequest(
      @NotBlank @Size(max = 150) String subject, @NotBlank @Size(max = 5000) String description) {}

  public record Feedback(@NotNull Boolean accepted) {}

  private final AiAssistant ai;

  public AiController(AiAssistant ai) {
    this.ai = ai;
  }

  @Operation(summary = "Is AI configured? The UI hides AI panels when it isn't.")
  @GetMapping("/api/v1/ai/status")
  public Status status() {
    return ai.status();
  }

  @Operation(
      summary = "Suggest category, priority and a summary for a new ticket (suggestion only)")
  @PostMapping("/api/v1/ai/triage")
  @PreAuthorize("hasAuthority('AI_USE')")
  public TriageSuggestion triage(
      @Valid @RequestBody TriageRequest request, @AuthenticationPrincipal AuthenticatedAgent me) {
    return ai.triage(request.subject(), request.description(), me);
  }

  @Operation(summary = "Summarise a ticket's timeline")
  @PostMapping("/api/v1/tickets/{id}/ai/summary")
  @PreAuthorize("hasAuthority('AI_USE')")
  public TimelineSummary summary(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedAgent me) {
    return ai.summarize(id, me);
  }

  @Operation(summary = "Draft a resolution note (the agent edits it before saving)")
  @PostMapping("/api/v1/tickets/{id}/ai/reply-draft")
  @PreAuthorize("hasAuthority('AI_USE')")
  public ReplyDraft replyDraft(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedAgent me) {
    return ai.draftReply(id, me);
  }

  @Operation(summary = "Was this suggestion useful? (measures AI value per prompt version)")
  @PatchMapping("/api/v1/ai/insights/{id}/feedback")
  @PreAuthorize("hasAuthority('AI_USE')")
  public ResponseEntity<Void> feedback(
      @PathVariable UUID id,
      @Valid @RequestBody Feedback feedback,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    ai.feedback(id, feedback.accepted(), me);
    return ResponseEntity.noContent().build();
  }
}
