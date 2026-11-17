package dev.untangle.escalation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.escalation.*} (§5.4, R4).
 *
 * @param schedulerEnabled run the SLA scheduler (off in tests, which call the job directly)
 * @param cron when to scan for breaches (default: every minute)
 * @param maxLevel highest automatic level; level n is reached at n × the SLA target after the clock
 *     start (level 1 = the deadline itself)
 */
@ConfigurationProperties("app.escalation")
public record EscalationProperties(boolean schedulerEnabled, String cron, int maxLevel) {
  public EscalationProperties {
    cron = cron == null || cron.isBlank() ? "0 * * * * *" : cron;
    maxLevel = maxLevel <= 0 ? 3 : maxLevel;
  }
}
