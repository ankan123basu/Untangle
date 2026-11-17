package dev.untangle.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for secrets we must be able to read back (partner HMAC secrets, ADR-016).
 *
 * <p>Why encryption and not hashing: to verify a webhook signature we have to recompute the HMAC,
 * which needs the original secret. A hash can't give it back.
 *
 * <p>Format: {@code v1:<base64url(12-byte IV)>:<base64url(ciphertext + 16-byte tag)>}
 *
 * <ul>
 *   <li>A fresh random IV for every encryption (never reuse an IV with GCM).
 *   <li>The GCM tag detects any tampering with the stored value.
 *   <li>{@code associatedData} (we pass the partner code) binds the ciphertext to its row, so
 *       copying partner A's encrypted secret onto partner B fails to decrypt.
 *   <li>The {@code v1} prefix lets us change algorithm or key later without guessing.
 * </ul>
 */
public final class SecretCipher {

  private static final String VERSION = "v1";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SecretKey key;

  public SecretCipher(byte[] keyBytes) {
    if (keyBytes == null || keyBytes.length != 32) {
      throw new IllegalStateException("AES-256 needs a 32-byte key");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
  }

  public String encrypt(String plaintext, String associatedData) {
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
      return VERSION + ":" + b64.encodeToString(iv) + ":" + b64.encodeToString(sealed);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Encryption failed", e);
    }
  }

  /**
   * @throws IllegalArgumentException if the value is malformed, was encrypted with another key or
   *     associated data, or has been tampered with
   */
  public String decrypt(String stored, String associatedData) {
    String[] parts = stored == null ? new String[0] : stored.split(":");
    if (parts.length != 3 || !VERSION.equals(parts[0])) {
      throw new IllegalArgumentException("Unsupported ciphertext format");
    }
    try {
      Base64.Decoder b64 = Base64.getUrlDecoder();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, b64.decode(parts[1])));
      cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(b64.decode(parts[2])), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Secret could not be decrypted", e);
    }
  }

  @Override
  public String toString() {
    return "SecretCipher[AES-256-GCM, key=<redacted>]";
  }
}
