/**
 * Untangle partner simulator.
 *
 *   node src/cli.ts --scenario all
 *   node src/cli.ts --scenario duplicate --base-url http://localhost:3000
 *
 * Setup uses the admin API (demo credentials by default) to create a fresh partner per run, so
 * results never depend on leftovers from earlier runs. Exit code 1 if any scenario fails.
 */
import { parseArgs } from 'node:util';
import { randomUUID } from 'node:crypto';
import { Untangle, type Partner } from './client.ts';
import { SCENARIOS, type Result } from './scenarios.ts';

const { values } = parseArgs({
  options: {
    scenario: { type: 'string', default: 'all' },
    'base-url': { type: 'string', default: process.env['SIM_BASE_URL'] ?? 'http://localhost:3000' },
    'admin-email': { type: 'string', default: process.env['SIM_ADMIN_EMAIL'] ?? 'admin@untangle.local' },
    'admin-password': { type: 'string', default: process.env['SIM_ADMIN_PASSWORD'] ?? 'Untangle@2026' },
    list: { type: 'boolean', default: false },
  },
});

if (values.list) {
  console.log(Object.keys(SCENARIOS).join('\n'));
  process.exit(0);
}

const names = values.scenario === 'all' ? Object.keys(SCENARIOS) : values.scenario.split(',');
const unknown = names.filter((n) => !(n in SCENARIOS));
if (unknown.length > 0) {
  console.error(`Unknown scenario(s): ${unknown.join(', ')}. Use --list.`);
  process.exit(2);
}

const api = new Untangle(values['base-url']);
const created: Partner[] = [];
const newPartner = async (rate: number): Promise<Partner> => {
  const partner = await api.createPartner(`sim-${randomUUID().slice(0, 8)}`, rate);
  created.push(partner);
  return partner;
};

console.log(`Untangle partner simulator → ${values['base-url']}`);
await api.login(values['admin-email'], values['admin-password']);
const partner = await newPartner(1000);
console.log(`Using fresh partner '${partner.code}' (secret received once from the admin API)\n`);

const results: Result[] = [];
for (const name of names) {
  try {
    results.push(await SCENARIOS[name]!({ api, partner, newPartner }));
  } catch (error) {
    results.push({ name, expected: '-', actual: `error: ${(error as Error).message}`, pass: false });
  }
}

// Leave the tickets (they're realistic demo data) but switch the throwaway partners off.
await Promise.all(created.map((p) => api.deactivate(p)));

const width = (key: keyof Result) => Math.max(key.length, ...results.map((r) => String(r[key]).length));
const [w1, w2, w3] = [width('name'), width('expected'), width('actual')];
const green = (s: string) => `\x1b[32m${s}\x1b[0m`;
const red = (s: string) => `\x1b[31m${s}\x1b[0m`;
console.log(`${'SCENARIO'.padEnd(w1)}  ${'EXPECTED'.padEnd(w2)}  ${'ACTUAL'.padEnd(w3)}  RESULT`);
console.log(`${'-'.repeat(w1)}  ${'-'.repeat(w2)}  ${'-'.repeat(w3)}  ------`);
for (const r of results) {
  console.log(`${r.name.padEnd(w1)}  ${r.expected.padEnd(w2)}  ${r.actual.padEnd(w3)}  ${r.pass ? green('PASS') : red('FAIL')}`);
}
const failed = results.filter((r) => !r.pass).length;
console.log(`\n${results.length - failed}/${results.length} scenarios passed`);
process.exit(failed === 0 ? 0 : 1);
