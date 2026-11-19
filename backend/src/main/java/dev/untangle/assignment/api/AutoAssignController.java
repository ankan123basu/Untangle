package dev.untangle.assignment.api;

import dev.untangle.assignment.application.AutoAssignAdmin;
import dev.untangle.assignment.application.AutoAssignViews.AutoAssignOverview;
import dev.untangle.assignment.application.AutoAssignViews.AutoAssignRun;
import dev.untangle.assignment.application.AutoAssignViews.UpdateAgentRouting;
import dev.untangle.assignment.application.AutoAssignViews.UpdateSettings;
import dev.untangle.assignment.application.AutoAssigner;
import dev.untangle.security.AuthenticatedAgent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Automatic assignment (ADR-029). Leads and admins (who may assign any ticket) switch it on, set
 * the per-agent cap, choose who takes automatic work, and can run it on demand.
 */
@Tag(name = "Auto-assign")
@RestController
@RequestMapping("/api/v1/auto-assign")
@PreAuthorize("hasAuthority('TICKET_ASSIGN_ANY')")
public class AutoAssignController {

  private final AutoAssignAdmin admin;
  private final AutoAssigner assigner;
  private final Clock clock;

  public AutoAssignController(AutoAssignAdmin admin, AutoAssigner assigner, Clock clock) {
    this.admin = admin;
    this.assigner = assigner;
    this.clock = clock;
  }

  @Operation(summary = "The switch, the cap, the waiting queue and each agent's routing and load")
  @GetMapping
  public AutoAssignOverview overview() {
    return admin.overview();
  }

  @Operation(summary = "Switch auto-assign on or off and set the per-agent cap")
  @PutMapping("/settings")
  public AutoAssignOverview updateSettings(
      @Valid @RequestBody UpdateSettings request, @AuthenticationPrincipal AuthenticatedAgent me) {
    return admin.updateSettings(request, me.id());
  }

  @Operation(summary = "Whether an agent takes automatic work, and which categories they handle")
  @PutMapping("/agents/{agentId}")
  public AutoAssignOverview updateAgent(
      @PathVariable UUID agentId, @Valid @RequestBody UpdateAgentRouting request) {
    return admin.updateAgent(agentId, request);
  }

  @Operation(summary = "Assign the waiting queue now (works even while the switch is off)")
  @PostMapping("/run")
  public AutoAssignRun runNow() {
    return assigner.assignWaiting(clock.instant());
  }
}
