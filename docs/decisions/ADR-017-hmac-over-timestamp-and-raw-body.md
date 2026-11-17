# ADR-017: HMAC-SHA256 over `timestamp + "." + rawBody`, verified before parsing

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 4

## Context

Partners must prove authenticity and integrity, and old captured requests must not be replayable.

## Options considered

1. **Shared API key in a header.** Proves identity but not integrity, and a leaked key works forever.
2. **mTLS.** Strong, but operationally heavy for partners and doesn't protect against replays by itself.
3. **HMAC over the body only.** Integrity yes, but a captured request can be replayed at any time.
4. **HMAC over `timestamp.rawBody` + a freshness window + idempotent event ids.** (Industry
   practice, e.g. Stripe and Slack.)

## Decision

Option 4.
- `X-Signature: sha256=hex(HMAC-SHA256(secret, X-Timestamp + "." + rawBody))`.
- The body is captured as **raw bytes** (`WebhookBodyLimitFilter` caches at most 64 KB, and the
  controller takes `byte[]`). The signature is checked on those bytes **before** any JSON parsing,
  never on re-serialised JSON.
- Constant-time comparison (`MessageDigest.isEqual`). The header format is strictly
  `sha256=` + 64 lowercase hex.
- Freshness: `|now − timestamp| ≤ 300 s`, rejecting future timestamps too. Duplicates within the
  window are absorbed by event-id idempotency (ADR-012).
- During rotation the previous secret is accepted until its grace expiry (24 h).

## Consequences

- **Pros:** authenticity, integrity and replay protection with plain HTTP+JSON; easy for partners
  (snippets for curl, Node, Java and Python, each verified against a shared test vector).
- **Cons:** needs reasonably synced clocks (±5 min); the secret must be readable by us (ADR-016).
- **Tests:** `WebhookCryptoTest` (OpenSSL reference vector, whitespace change, malformed headers),
  `WebhookIntegrationTest` (tampered, whitespace-only, wrong secret, expired/future/non-numeric
  timestamps, rotation in and out of grace).

## Simple explanation

Each request carries a fingerprint made from the message, the time it was sent and a shared secret.
Change one byte, reuse an old message, or not know the secret, and the fingerprint won't match.
