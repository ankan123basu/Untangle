package dev.untangle.comment.domain;

/** Kinds of timeline entries on a ticket. */
public enum CommentType {
  /** A note written by a person. */
  COMMENT,
  /** The explanation of how a ticket was resolved. Required to move a ticket to RESOLVED (A4). */
  RESOLUTION_NOTE,
  /** Written by the system: status changes, assignments, escalations, SLA breaches. */
  SYSTEM
}
