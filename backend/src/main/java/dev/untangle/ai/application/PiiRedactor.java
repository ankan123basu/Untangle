package dev.untangle.ai.application;

import java.util.regex.Pattern;

/**
 * Removes personal data before text leaves our system for the AI provider (§10 "PII minimisation").
 * Order matters: cards and long numbers are matched before phone numbers, so a 16-digit card isn't
 * half-masked as a phone.
 *
 * <ul>
 *   <li>emails → {@code [EMAIL]}
 *   <li>card-like numbers: 13–19 digits, optionally grouped with spaces or dashes → {@code [CARD]}
 *   <li>other long numbers (account, Aadhaar, IFSC-free digit runs of 9+) → {@code [NUMBER]}
 *   <li>phone numbers (+country code, 10-digit mobiles, grouped formats) → {@code [PHONE]}
 * </ul>
 *
 * It's a safety net, not a guarantee. That's why AI is off by default and open question R10 asks
 * the lead to approve sending redacted text to a third party.
 */
public final class PiiRedactor {

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

  /** Indian mobile numbers: optional +91, then 10 digits starting 6-9. */
  private static final Pattern MOBILE =
      Pattern.compile("(?<!\\d)(?:\\+?91[ -]?)?[6-9]\\d{9}(?!\\d)");

  private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{9,}\\b");
  private static final Pattern PHONE =
      Pattern.compile(
          "(?<![\\w])(?:\\+\\d{1,3}[ -]?)?(?:\\(?\\d{2,5}\\)?[ -]?){1,3}\\d{3,5}(?![\\w])");

  private PiiRedactor() {}

  public static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = EMAIL.matcher(text).replaceAll("[EMAIL]");
    out = CARD.matcher(out).replaceAll("[CARD]");
    out = MOBILE.matcher(out).replaceAll("[PHONE]");
    out = LONG_NUMBER.matcher(out).replaceAll("[NUMBER]");
    out = PHONE.matcher(out).replaceAll(m -> digits(m.group()) >= 8 ? "[PHONE]" : m.group());
    return out;
  }

  private static int digits(String s) {
    int n = 0;
    for (char c : s.toCharArray()) {
      if (Character.isDigit(c)) n++;
    }
    return n;
  }
}
