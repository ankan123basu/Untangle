# Webhook integration guide (for partners)

## Simple explanation

Your system can create support tickets in Untangle automatically by sending an HTTPS `POST` with
the complaint as JSON. To prove the request really comes from you, you sign it with a secret we give
you once. If you send the same event twice (for example after a timeout), we recognise it and
return the ticket we already created, so retrying is always safe.

## Technical details

### Endpoint

```
POST /api/v1/webhooks/partners/tickets
Content-Type: application/json
```

### Headers

| Header | Value | Rules |
|---|---|---|
| `X-Partner-Id` | your partner code, e.g. `acme-bank` | lowercase letters, digits, dashes |
| `X-Event-Id` | your unique id for this complaint, e.g. `evt_01HZX...` | 1–128 chars `[A-Za-z0-9._:-]`. **Reuse it on retries.** Unique per partner |
| `X-Timestamp` | Unix time in **seconds** when you sign | must be within **±300 s** of our clock |
| `X-Signature` | `sha256=<lowercase hex>` | see below |

### Body

```json
{
  "customerName": "Rahul Sharma",
  "customerEmail": "rahul@example.com",
  "subject": "Refund not received",
  "description": "I cancelled my order 7 days ago and the refund has not arrived.",
  "category": "REFUND",
  "priority": "HIGH"
}
```

| Field | Required | Rules |
|---|---|---|
| `customerName` | yes | 2–120 characters |
| `customerEmail` | no | valid email |
| `subject` | no | ≤ 150; if missing we use the first 80 characters of `description` |
| `description` | yes | 10–5000 characters |
| `category` | yes | `BILLING`, `TECHNICAL`, `ACCOUNT`, `REFUND`, `GENERAL`, `OTHER` (exact, uppercase) |
| `priority` | yes | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` (exact, uppercase) |

Unknown extra fields are ignored. Maximum body size: **64 KB**.

### Signing algorithm

```
message   = X-Timestamp + "." + rawBody          (rawBody = the exact bytes you send)
signature = lowercase_hex( HMAC-SHA256( key = your secret as UTF-8 bytes, message ) )
X-Signature: sha256=<signature>
```

Rules that trip people up:

- Sign the **exact bytes** you send. Serialise your JSON once, sign that string, and send that same
  string. Re-serialising (even adding one space) breaks the signature.
- The timestamp is **inside** the signature, so an old captured request can't be replayed later.
- Your secret is the string we gave you; use its UTF-8 bytes as the HMAC key (don't base64-decode it).

Test vector: secret `top-secret`, timestamp `1700000000`, body `{"a":1}` →
`sha256=d2025794836f47aaa594a141e200ddfcf1ac5c703eeec4de43cd38a72262c5f3`

### Examples (each verified to produce the test vector above)

**curl / bash**

```bash
SECRET='acme-demo-secret-change-me'           # demo partner acme-bank (demo profile)
BODY='{"customerName":"Rahul Sharma","description":"I cancelled my order 7 days ago and the refund has not arrived.","category":"REFUND","priority":"HIGH"}'
TS=$(date +%s)
SIG="sha256=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')"
curl -X POST http://localhost:3000/api/v1/webhooks/partners/tickets \
  -H 'Content-Type: application/json' \
  -H 'X-Partner-Id: acme-bank' \
  -H "X-Event-Id: evt_$TS" \
  -H "X-Timestamp: $TS" \
  -H "X-Signature: $SIG" \
  -d "$BODY"
```

**Node.js**

```js
import { createHmac } from 'node:crypto';

function sign(secret, timestamp, rawBody /* Buffer */) {
  return 'sha256=' + createHmac('sha256', secret).update(`${timestamp}.`).update(rawBody).digest('hex');
}

const body = Buffer.from(JSON.stringify(payload));      // serialise ONCE
const ts = String(Math.floor(Date.now() / 1000));
await fetch(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Partner-Id': 'acme-bank',
             'X-Event-Id': eventId, 'X-Timestamp': ts, 'X-Signature': sign(secret, ts, body) },
  body,                                                  // send the SAME bytes
});
```

**Java**

```java
static String sign(String secret, String timestamp, byte[] rawBody) throws Exception {
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
  return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
}
```

**Python**

```python
import hmac, hashlib

def sign(secret: str, timestamp: str, raw_body: bytes) -> str:
    message = timestamp.encode("ascii") + b"." + raw_body
    return "sha256=" + hmac.new(secret.encode("utf-8"), message, hashlib.sha256).hexdigest()
```

### Responses

| Status | Body | Meaning | What you should do |
|---|---|---|---|
| **201** | `{"ticketId", "ticketNumber", "duplicate": false}` | ticket created | done |
| **200** | `{"ticketId", "ticketNumber", "duplicate": true}` | we already have this `X-Event-Id`; here's the same ticket | done (your retry was safe) |
| **400** | Problem Details with `errors[{field, message}]` | your data is invalid (signature was fine) | fix the payload; retrying unchanged won't help |
| **401** | Problem Details, `code: WEBHOOK_REJECTED`, detail `invalid webhook request` | authentication failed (deliberately vague) | check partner id, secret, signature and clock; ask us for the exact reason |
| **413** | `code: PAYLOAD_TOO_LARGE` | body > 64 KB | shorten the description |
| **429** | `code: RATE_LIMITED`, header `Retry-After: <seconds>` | over your per-minute limit (default 60) | wait `Retry-After` seconds, then retry with the **same** event id |
| **5xx** | | our problem | retry with backoff and the **same** event id |

### Retries

- Retry on network errors, timeouts, 429 and 5xx, with exponential backoff (e.g. 1 s, 2 s, 4 s ... up
  to 5 min). **Always reuse the original `X-Event-Id`**: that's what makes retries safe.
- Re-sign every attempt with a **fresh timestamp** (an attempt more than 5 minutes after signing is
  rejected).
- Don't retry 400 or 401 automatically.

### Secret rotation

When we rotate your secret, the new one is shown once to our admin and handed to you. The **old
secret keeps working for 24 hours**, so you can deploy the new one without downtime.

### Why a 401 doesn't tell you what's wrong

Telling an attacker "signature mismatch" versus "unknown partner" would help them probe. Our admins
see the exact reason for every attempt in the webhook delivery log (`Admin → Webhooks`); contact
them with your `X-Event-Id` and the response's `traceId`.

### Try it locally

```bash
make up            # demo profile: partner acme-bank, secret acme-demo-secret-change-me
make simulate      # runs all 13 partner scenarios and prints a pass/fail table
```
