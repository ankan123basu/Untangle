package dev.untangle.webhook.hmac;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The webhook signature scheme (§7.1, ADR-017):
 *
 * <pre>
 *   X-Signature: sha256=hex( HMAC-SHA256( key = secret (UTF-8), message = timestamp + "." + rawBody ) )
 * </pre>
 *
 * <ul>
 *   <li>The <b>raw body bytes</b> are signed, exactly as received. Re-serialised JSON could differ
 *       by one space and break verification, or worse, let different bytes pass.
 *   <li>The <b>timestamp is inside the signed message</b>, so an attacker can't replay an old body
 *       with a fresh timestamp.
 *   <li>Comparison is <b>constant-time</b> ({@link MessageDigest#isEqual}), so timing doesn't leak
 *       how many leading bytes matched.
 * </ul>
 */
public final class HmacSignatures {

  public static final String PREFIX = "sha256=";
  private static final Pattern FORMAT = Pattern.compile("sha256=[0-9a-f]{64}");

  private HmacSignatures() {}

  /** Returns the {@code X-Signature} header value for this secret, timestamp and body. */
  public static String sign(String secret, String timestamp, byte[] rawBody) {
    return PREFIX + HexFormat.of().formatHex(mac(secret, timestamp, rawBody));
  }

  /** True if {@code header} has the expected format. */
  public static boolean isWellFormed(String header) {
    return header != null && FORMAT.matcher(header).matches();
  }

  /** Constant-time check of a well-formed {@code header} against the expected signature. */
  public static boolean verify(String secret, String timestamp, byte[] rawBody, String header) {
    if (!isWellFormed(header)) {
      return false;
    }
    byte[] expected = mac(secret, timestamp, rawBody);
    byte[] presented = HexFormat.of().parseHex(header.substring(PREFIX.length()));
    return MessageDigest.isEqual(expected, presented);
  }

  private static byte[] mac(String secret, String timestamp, byte[] rawBody) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
      mac.update((byte) '.');
      mac.update(rawBody);
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
  }

  /** SHA-256 hex of the body, stored in the delivery log instead of the body itself. */
  public static String sha256Hex(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
