import { randomUUID } from 'node:crypto';
import type { Partner, Untangle } from './client.ts';
import { complaint } from './payloads.ts';
import { nowSeconds } from './signing.ts';

export interface Result {
  name: string;
  expected: string;
  actual: string;
  pass: boolean;
}

export interface Context {
  api: Untangle;
  partner: Partner;
  /** Creates throwaway partners for scenarios that need special settings. */
  newPartner: (ratePerMinute: number) => Promise<Partner>;
}

type Scenario = (ctx: Context) => Promise<Result>;

const result = (name: string, expected: string, actual: string, pass: boolean): Result => ({
  name, expected, actual, pass,
});

const single = (name: string, expected: number, run: (ctx: Context) => Promise<number>): Scenario =>
  async (ctx) => {
    const status = await run(ctx);
    return result(name, String(expected), String(status), status === expected);
  };

export const SCENARIOS: Record<string, Scenario> = {
  valid: single('valid', 201, async ({ api, partner }) =>
    (await api.sendWebhook(partner, complaint())).status),

  duplicate: async ({ api, partner }) => {
    const eventId = `evt_${randomUUID()}`;
    const body = complaint();
    const first = await api.sendWebhook(partner, body, { eventId });
    const second = await api.sendWebhook(partner, body, { eventId });
    const sameTicket = first.body['ticketId'] === second.body['ticketId'];
    return result('duplicate', '201 then 200, same ticket',
      `${first.status} then ${second.status}${sameTicket ? ', same ticket' : ', DIFFERENT ticket'}`,
      first.status === 201 && second.status === 200 && sameTicket);
  },

  'concurrent-duplicate': async ({ api, partner }) => {
    const eventId = `evt_${randomUUID()}`;
    const body = complaint();
    const replies = await Promise.all(
      Array.from({ length: 20 }, () => api.sendWebhook(partner, body, { eventId })));
    const created = replies.filter((r) => r.status === 201).length;
    const duplicates = replies.filter((r) => r.status === 200).length;
    const tickets = new Set(replies.map((r) => r.body['ticketId'])).size;
    return result('concurrent-duplicate', '20 parallel → 1×201, 19×200, 1 ticket',
      `${created}×201, ${duplicates}×200, ${tickets} ticket(s)`,
      created === 1 && duplicates === 19 && tickets === 1);
  },

  'tampered-body': single('tampered-body', 401, async ({ api, partner }) => {
    const signed = complaint({ priority: 'LOW' });
    const sent = Buffer.from(signed.toString('utf8').replace('"LOW"', '"CRITICAL"'));
    return (await api.sendWebhook(partner, sent, { signedBody: signed })).status;
  }),

  'wrong-secret': single('wrong-secret', 401, async ({ api, partner }) =>
    (await api.sendWebhook(partner, complaint(), { signWith: 'definitely-not-the-secret' })).status),

  'expired-timestamp': single('expired-timestamp', 401, async ({ api, partner }) =>
    (await api.sendWebhook(partner, complaint(), { timestamp: nowSeconds(-600) })).status),

  'future-timestamp': single('future-timestamp', 401, async ({ api, partner }) =>
    (await api.sendWebhook(partner, complaint(), { timestamp: nowSeconds(600) })).status),

  'missing-signature': single('missing-signature', 401, async ({ api, partner }) =>
    (await api.sendWebhook(partner, complaint(), { omitHeaders: ['X-Signature'] })).status),

  'unknown-partner': single('unknown-partner', 401, async ({ api }) =>
    (await api.sendWebhook({ id: null, code: 'no-such-partner', secret: 'x' }, complaint())).status),

  'invalid-priority': single('invalid-priority', 400, async ({ api, partner }) =>
    (await api.sendWebhook(partner, complaint({ priority: 'URGENT' }))).status),

  'oversized-payload': single('oversized-payload', 413, async ({ api, partner }) =>
    (await api.sendWebhook(partner, complaint({ description: 'x'.repeat(70 * 1024) }))).status),

  burst: async ({ api, newPartner }) => {
    const limited = await newPartner(5);
    const replies = await Promise.all(
      Array.from({ length: 10 }, () => api.sendWebhook(limited, complaint())));
    const created = replies.filter((r) => r.status === 201).length;
    const throttled = replies.filter((r) => r.status === 429).length;
    return result('burst', 'limit 5/min, 10 sent → some 429', `${created}×201, ${throttled}×429`,
      created === 5 && throttled === 5);
  },

  rotation: async ({ api, newPartner }) => {
    const partner = await newPartner(100);
    const oldSecret = partner.secret;
    const newSecret = await api.rotateSecret(partner);
    const withOld = await api.sendWebhook(partner, complaint(), { signWith: oldSecret });
    const withNew = await api.sendWebhook(partner, complaint(), { signWith: newSecret });
    return result('rotation', 'old secret in grace → 201, new → 201',
      `old → ${withOld.status}, new → ${withNew.status}`,
      withOld.status === 201 && withNew.status === 201);
  },
};
