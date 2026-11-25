package dev.untangle.dashboard.api;

import dev.untangle.security.AuthenticatedAgent;
import dev.untangle.ticket.application.AgentPerformanceStats;
import dev.untangle.ticket.application.AgentPerformanceStats.PerformanceReport;
import dev.untangle.ticket.application.TicketStatistics;
import dev.untangle.ticket.application.TicketStatistics.DayTrend;
import dev.untangle.ticket.application.TicketStatistics.Summary;
import dev.untangle.ticket.application.TicketStatistics.Workload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard (who: everyone on sign-in; leads for workload and agent performance. Why: "what needs
 * attention right now"). Requirement 6: {@code unresolved} is the number of OPEN + IN_PROGRESS
 * tickets (A5).
 */
@Tag(name = "Dashboard")
@Validated
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

  private final TicketStatistics statistics;
  private final AgentPerformanceStats performance;

  public DashboardController(TicketStatistics statistics, AgentPerformanceStats performance) {
    this.statistics = statistics;
    this.performance = performance;
  }

  @Operation(summary = "Unresolved count and breakdowns")
  @GetMapping("/summary")
  @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
  public Summary summary(@AuthenticationPrincipal AuthenticatedAgent me) {
    return statistics.summary(me.id());
  }

  @Operation(summary = "Created vs resolved per UTC day")
  @GetMapping("/trends")
  @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
  public List<DayTrend> trends(@RequestParam(defaultValue = "14") @Min(1) @Max(90) int days) {
    return statistics.trends(days);
  }

  @Operation(summary = "Open/in-progress/breached tickets per active agent")
  @GetMapping("/agents-workload")
  @PreAuthorize("hasAuthority('AGENT_WORKLOAD_VIEW')")
  public List<Workload> workload() {
    return statistics.workload();
  }

  @Operation(
      summary = "Resolution speed, SLA met and reopen rate per agent (ADR-030)",
      description =
          "Leads and admins. Resolutions in the last `days` days, credited to the assignee.")
  @GetMapping("/agent-performance")
  @PreAuthorize("hasAuthority('AGENT_WORKLOAD_VIEW')")
  public PerformanceReport agentPerformance(
      @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days) {
    return performance.report(days);
  }
}
