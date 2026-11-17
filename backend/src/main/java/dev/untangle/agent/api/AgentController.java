package dev.untangle.agent.api;

import dev.untangle.agent.application.AgentCommands.CreateAgent;
import dev.untangle.agent.application.AgentCommands.SetActive;
import dev.untangle.agent.application.AgentCommands.UpdateAgent;
import dev.untangle.agent.application.AgentService;
import dev.untangle.agent.application.AgentViews.AgentAdminView;
import dev.untangle.agent.application.AgentViews.AgentOption;
import dev.untangle.security.AuthenticatedAgent;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agents API.
 *
 * <ul>
 *   <li>{@code GET /api/v1/agents}: any signed-in user. Active agents, minimal fields, for
 *       assignment dropdowns.
 *   <li>{@code GET /api/v1/admin/agents}: admins. Full list for the team-management screen.
 *   <li>{@code POST/PATCH /api/v1/agents...}: admins.
 * </ul>
 *
 * The controller only validates input and delegates; every rule lives in {@link AgentService}.
 */
@RestController
public class AgentController {

  private final AgentService agentService;

  public AgentController(AgentService agentService) {
    this.agentService = agentService;
  }

  @GetMapping("/api/v1/agents")
  @PreAuthorize("hasAuthority('TICKET_VIEW')")
  public List<AgentOption> listOptions() {
    return agentService.listActiveOptions();
  }

  @GetMapping("/api/v1/admin/agents")
  @PreAuthorize("hasAuthority('AGENT_MANAGE')")
  public List<AgentAdminView> listForAdmin() {
    return agentService.listForAdmin();
  }

  @PostMapping("/api/v1/agents")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAuthority('AGENT_MANAGE')")
  public AgentAdminView create(@Valid @RequestBody CreateAgent request) {
    return agentService.create(request);
  }

  @PatchMapping("/api/v1/agents/{id}")
  @PreAuthorize("hasAuthority('AGENT_MANAGE')")
  public AgentAdminView update(@PathVariable UUID id, @Valid @RequestBody UpdateAgent request) {
    return agentService.update(id, request);
  }

  @PatchMapping("/api/v1/agents/{id}/active")
  @PreAuthorize("hasAuthority('AGENT_MANAGE')")
  public AgentAdminView setActive(
      @PathVariable UUID id,
      @Valid @RequestBody SetActive request,
      @AuthenticationPrincipal AuthenticatedAgent me) {
    return agentService.setActive(id, request.active(), me.id());
  }
}
