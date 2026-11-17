# ADR-016: Partner HMAC secrets are encrypted with AES-256-GCM, not hashed

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 4

## Context

To verify a webhook we recompute `HMAC(secret, timestamp.body)`, so we need the original secret on
every request. Storing it in plain text would hand every partner's secret to anyone who reads the
database or a backup.

## Options considered

1. **Plain text.** Simple; a DB leak means attackers can forge tickets as any partner.
2. **Hash (bcrypt/SHA-256), like passwords.** Impossible: a hash can't be reversed to compute an HMAC.
3. **Symmetric encryption with an app-held key** (AES-256-GCM, key in an env var).
4. **External KMS / Vault.** Best practice at scale; it's another service to run and is out of scope
   for this project. The cipher is isolated behind `SecretCipher`, so it can be swapped later.

## Decision

Option 3.
- `SecretCipher`: AES-256-GCM, a random 12-byte IV per encryption, a 128-bit tag, and the partner
  code as associated data (the ciphertext is bound to its row).
- Format `v1:<iv>:<ciphertext+tag>` (base64url). The prefix allows a future key or algorithm change.
- The key comes from `APP_SECRET_ENCRYPTION_KEY` (exactly 32 bytes); the app refuses to start
  without it. `make .env` generates one.
- Secrets are generated server-side (32 random bytes, base64url), shown once at create/rotate, and
  never logged or returned again.

## Consequences

- **Pros:** a DB dump alone doesn't reveal secrets; tampering is detected (GCM tag); one row's
  ciphertext can't be pasted onto another partner.
- **Cons:** whoever holds both the DB and the env key can decrypt. Losing the key makes all partner
  secrets unrecoverable (rotate them all; runbook). There's no key rotation tooling yet; the `v1`
  prefix leaves room for it.
- **Tests:** `WebhookCryptoTest` (fresh IV, wrong AAD, tampering, wrong key, fail-fast key),
  `PartnerAdminIntegrationTest.secretIsShownOnceAndStoredOnlyEncrypted`.

## Simple explanation

We have to be able to read partner secrets back to check signatures, so we lock them with a key
kept outside the database instead of scrambling them one-way like passwords.
