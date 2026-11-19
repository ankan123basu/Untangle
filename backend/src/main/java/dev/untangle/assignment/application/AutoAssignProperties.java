package dev.untangle.assignment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.assignment.*} (ADR-029). Whether auto-assign is on is a runtime setting (the
 * Auto-assign screen); these only control the background sweep.
 *
 * @param schedulerEnabled run the periodic sweep (off in tests, which call it directly)
 * @param cron how often to sweep the waiting queue (default: every 30 seconds)
 */
@ConfigurationProperties("app.assignment")
public record AutoAssignProperties(boolean schedulerEnabled, String cron) {
  public AutoAssignProperties {
    cron = cron == null || cron.isBlank() ? "*/30 * * * * *" : cron;
  }
}
