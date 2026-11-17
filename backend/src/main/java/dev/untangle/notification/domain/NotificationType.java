package dev.untangle.notification.domain;

/** Why someone is being notified. */
public enum NotificationType {
  ASSIGNED,
  ESCALATED,
  SLA_BREACH,
  MENTION,
  REOPENED
}
