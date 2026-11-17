package dev.untangle.webhook.domain;

/**
 * Result of one webhook attempt. Partners only ever see a generic 401 for rejections; admins see
 * the precise outcome and reason in the delivery log (Section 7.2).
 */
public enum DeliveryOutcome {
  CREATED,
  DUPLICATE,
  REJECTED_SIGNATURE,
  REJECTED_TIMESTAMP,
  REJECTED_UNKNOWN_PARTNER,
  REJECTED_VALIDATION,
  REJECTED_TOO_LARGE,
  RATE_LIMITED
}
