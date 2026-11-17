package dev.untangle.auth.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Refresh token values: 256 random bits, URL-safe. Only their SHA-256 is ever stored.
 *
 * <p>Why SHA-256 and not BCrypt? The token is already high-entropy random data, so it can't be
 * brute-forced; a fast hash is enough to make a leaked database useless, and it allows an indexed
 * equality lookup ({@code WHERE token_hash = ?}). Passwords are low-entropy, which is why they get
 * slow BCrypt.
 */
final class RefreshTokens {

  private static final SecureRandom RANDOM = new SecureRandom();

  private RefreshTokens() {}

  static String newRawToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String sha256Hex(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
