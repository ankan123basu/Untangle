package dev.untangle.common.util;

/**
 * Helpers for keeping personal data out of logs (Section 6.3). Use them for every email that
 * appears in a log message.
 */
public final class Masking {

  private Masking() {}

  /** {@code rahul.sharma@example.com} becomes {@code r***@example.com}. */
  public static String email(String email) {
    if (email == null || email.isBlank()) {
      return "<none>";
    }
    int at = email.indexOf('@');
    if (at <= 0) {
      return "***";
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
