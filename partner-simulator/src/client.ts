import { randomUUID } from 'node:crypto';
import { nowSeconds, sign } from './signing.ts';

export interface Partner {
  id: string | null;
  code: string;
  secret: string;
}

export interface Reply {
  status: number;
  body: Record<string, unknown>;
}

export interface WebhookOptions {
  eventId?: string;
  timestamp?: string;
  /** Secret used to sign; defaults to the partner's own. */
  signWith?: string;
  /** Timestamp that was signed, if different from the X-Timestamp sent (timestamp swap). */
  signedTimestamp?: string;
  /** Bytes that were signed, if different from the bytes sent (tampering). */
  signedBody?: Buffer;
  omitHeaders?: string[];
}

const WEBHOOK_PATH = '/api/v1/webhooks/partners/tickets';

/** Thin HTTP client for the two roles the simulator plays: an admin (setup) and a partner. */
export class Untangle {
  private token = '';
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  async login(email: string, password: string): Promise<void> {
    const reply = await this.json('POST', '/api/v1/auth/login', { email, password });
    if (reply.status !== 200) {
      throw new Error(`Admin login failed (${reply.status}): ${JSON.stringify(reply.body)}`);
    }
    this.token = String(reply.body['accessToken']);
  }

  async createPartner(code: string, rateLimitPerMinute: number): Promise<Partner> {
    const reply = await this.json('POST', '/api/v1/admin/partners', {
      code,
      name: `Simulator ${code}`,
      rateLimitPerMinute,
    });
    if (reply.status !== 201) {
      throw new Error(`Creating partner failed (${reply.status}): ${JSON.stringify(reply.body)}`);
    }
    const partner = reply.body['partner'] as Record<string, unknown>;
    return { id: String(partner['id']), code, secret: String(reply.body['secret']) };
  }

  async rotateSecret(partner: Partner): Promise<string> {
    const reply = await this.json('POST', `/api/v1/admin/partners/${partner.id}/rotate-secret`);
    if (reply.status !== 200) throw new Error(`Rotation failed (${reply.status})`);
    return String(reply.body['secret']);
  }

  async deactivate(partner: Partner): Promise<void> {
    await this.json('PATCH', `/api/v1/admin/partners/${partner.id}/active`, { active: false });
  }

  /** Sends a webhook exactly like a partner would (or deliberately wrongly, for negative tests). */
  async sendWebhook(partner: Partner, body: Buffer, options: WebhookOptions = {}): Promise<Reply> {
    const timestamp = options.timestamp ?? nowSeconds();
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'X-Partner-Id': partner.code,
      'X-Event-Id': options.eventId ?? `evt_${randomUUID()}`,
      'X-Timestamp': timestamp,
      'X-Signature': sign(
        options.signWith ?? partner.secret,
        options.signedTimestamp ?? timestamp,
        options.signedBody ?? body,
      ),
    };
    for (const name of options.omitHeaders ?? []) delete headers[name];
    // Send the exact bytes that were (or deliberately weren't) signed.
    const response = await fetch(this.baseUrl + WEBHOOK_PATH, {
      method: 'POST',
      headers,
      body: new Uint8Array(body),
    });
    return { status: response.status, body: await safeJson(response) };
  }

  private async json(method: string, path: string, payload?: unknown): Promise<Reply> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.token) headers['Authorization'] = `Bearer ${this.token}`;
    const init: RequestInit = { method, headers };
    if (payload !== undefined) init.body = JSON.stringify(payload);
    const response = await fetch(this.baseUrl + path, init);
    return { status: response.status, body: await safeJson(response) };
  }
}

async function safeJson(response: Response): Promise<Record<string, unknown>> {
  const text = await response.text();
  try {
    return text ? (JSON.parse(text) as Record<string, unknown>) : {};
  } catch {
    return { raw: text.slice(0, 200) };
  }
}
