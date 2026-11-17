/**
 * The partner side of the signature scheme (docs/webhook-integration-guide.md):
 *
 *   X-Signature: sha256=hex( HMAC-SHA256( key = secret (UTF-8), message = timestamp + "." + rawBody ) )
 *
 * Deliberately shares no code with the backend: a real partner wouldn't have our code either. If
 * this file and the backend disagree, the "valid" scenario fails.
 */
import { createHmac } from 'node:crypto';

export function sign(secret: string, timestamp: string, rawBody: Buffer): string {
  const hmac = createHmac('sha256', Buffer.from(secret, 'utf8'));
  hmac.update(`${timestamp}.`, 'utf8');
  hmac.update(rawBody);
  return `sha256=${hmac.digest('hex')}`;
}

export function nowSeconds(offsetSeconds = 0): string {
  return String(Math.floor(Date.now() / 1000) + offsetSeconds);
}
