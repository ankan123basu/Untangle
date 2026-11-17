package dev.untangle.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.untangle.ai.application.PiiRedactor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** §10: personal data never leaves for the AI provider. */
class PiiRedactorTest {

  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          Mail me at rahul.sharma+bank@example.co.in today | Mail me at [EMAIL] today
          Card 4111 1111 1111 1111 was charged            | Card [CARD] was charged
          Card 4111-1111-1111-1111 was charged            | Card [CARD] was charged
          Card 4111111111111111 was charged               | Card [CARD] was charged
          Account 123456789012 is frozen                  | Account [NUMBER] is frozen
          Call +91 98765 43210 please                     | Call [PHONE] please
          Call 9876543210 please                          | Call [PHONE] please
          Call (020) 7946-0958 please                     | Call [PHONE] please
          """)
  void redactsPersonalData(String input, String expected) {
    assertThat(PiiRedactor.redact(input)).isEqualTo(expected);
  }

  @Test
  void keepsOrdinaryNumbersAndText() {
    String text =
        "Refund of Rs 2,499 for order 7 days ago, ticket UNT-000123, at 10:30 on 2026-11-18";
    assertThat(PiiRedactor.redact(text)).isEqualTo(text);
  }

  @Test
  void handlesEmptyInput() {
    assertThat(PiiRedactor.redact(null)).isNull();
    assertThat(PiiRedactor.redact("")).isEmpty();
  }
}
