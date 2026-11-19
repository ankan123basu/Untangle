package dev.untangle.assignment.application;

import dev.untangle.agent.application.AgentDirectory;
import dev.untangle.agent.application.AgentService;
import dev.untangle.agent.application.AgentSnapshot;
import dev.untangle.agent.application.AgentViews.AgentOption;
import dev.untangle.agent.domain.Role;
import dev.untangle.assignment.application.AutoAssignViews.AgentRoutingView;
import dev.untangle.assignment.application.AutoAssignViews.AutoAssignOverview;
import dev.untangle.assignment.application.AutoAssignViews.UpdateAgentRouting;
import dev.untangle.assignment.application.AutoAssignViews.UpdateSettings;
import dev.untangle.assignment.domain.AgentRouting;
import dev.untangle.assignment.domain.AssignmentPlanner;
import dev.untangle.assignment.domain.AutoAssignSettings;
import dev.untangle.assignment.infrastructure.AgentRoutingRepository;
import dev.untangle.assignment.infrastructure.AutoAssignSettingsRepository;
import dev.untangle.common.error.ApiException;
import dev.untangle.common.error.ErrorCode;
import dev.untangle.ticket.application.TicketQueue;
import dev.untangle.ticket.application.TicketQueue.Workload;
import dev.untangle.ticket.domain.Category;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The Auto-assign screen (ADR-029): the switch, the cap, and who takes which work. */
@Service
public class AutoAssignAdmin {

  private static final Logger log = LoggerFactory.getLogger(AutoAssignAdmin.class);

  private final AutoAssignSettingsRepository settings;
  private final AgentRoutingRepository routing;
  private final AgentService agents;
  private final AgentDirectory directory;
  private final TicketQueue queue;

  public AutoAssignAdmin(
      AutoAssignSettingsRepository settings,
      AgentRoutingRepository routing,
      AgentService agents,
      AgentDirectory directory,
      TicketQueue queue) {
    this.settings = settings;
    this.routing = routing;
    this.agents = agents;
    this.directory = directory;
    this.queue = queue;
  }

  @Transactional(readOnly = true)
  public AutoAssignOverview overview() {
    AutoAssignSettings current =
        settings.findFirstByOrderByCreatedAtAsc().orElseGet(AutoAssignSettings::defaults);
    Map<UUID, AgentRouting> configured =
        routing.findAll().stream()
            .collect(Collectors.toMap(AgentRouting::getAgentId, Function.identity()));
    Map<UUID, Workload> loads = queue.workloads();
    List<AgentRoutingView> rows =
        agents.listActiveOptions().stream()
            .map(agent -> view(agent, configured.get(agent.id()), loads.get(agent.id())))
            .sorted(
                Comparator.comparing(AgentRoutingView::autoAssign)
                    .reversed()
                    .thenComparing(AgentRoutingView::ai)
                    .thenComparing(AgentRoutingView::fullName))
            .toList();
    return new AutoAssignOverview(
        current.isEnabled(), current.getMaxOpenPerAgent(), queue.waitingCount(), rows);
  }

  @Transactional
  public AutoAssignOverview updateSettings(UpdateSettings command, UUID actorId) {
    AutoAssignSettings current =
        settings.findFirstByOrderByCreatedAtAsc().orElseGet(AutoAssignSettings::defaults);
    current.change(command.enabled(), command.maxOpenPerAgent(), actorId);
    settings.saveAndFlush(current);
    log.info(
        "Auto-assign settings changed enabled={} maxOpen={}",
        command.enabled(),
        command.maxOpenPerAgent());
    return overview();
  }

  @Transactional
  public AutoAssignOverview updateAgent(UUID agentId, UpdateAgentRouting command) {
    AgentSnapshot agent =
        directory
            .findFresh(agentId)
            .filter(AgentSnapshot::active)
            .orElseThrow(() -> new ApiException(ErrorCode.AGENT_NOT_FOUND));
    AgentRouting r =
        routing
            .findByAgentId(agentId)
            .orElseGet(() -> new AgentRouting(agentId, command.autoAssign(), command.skills()));
    r.change(command.autoAssign(), command.skills());
    routing.saveAndFlush(r);
    log.info(
        "Agent routing changed id={} autoAssign={} skills={}",
        agentId,
        command.autoAssign(),
        command.skills().size());
    if (agent.ai() && command.autoAssign()) {
      log.info("AI teammate opted in to automatic LOW/MEDIUM work");
    }
    return overview();
  }

  private static AgentRoutingView view(AgentOption agent, AgentRouting r, Workload load) {
    Map<?, Integer> open = load == null ? Map.of() : load.byPriority();
    int openCount = open.values().stream().mapToInt(Integer::intValue).sum();
    int weighted =
        load == null
            ? 0
            : load.byPriority().entrySet().stream()
                .mapToInt(e -> AssignmentPlanner.weight(e.getKey()) * e.getValue())
                .sum();
    boolean auto =
        r == null
            ? AgentRouting.defaultAutoAssign(agent.role() == Role.AGENT, agent.ai())
            : r.isAutoAssign();
    List<Category> skills = r == null ? List.of() : r.getSkills().stream().sorted().toList();
    return new AgentRoutingView(
        agent.id(),
        agent.fullName(),
        agent.role(),
        agent.ai(),
        auto,
        skills,
        openCount,
        weighted,
        r == null ? null : r.getLastAutoAssignedAt());
  }
}
