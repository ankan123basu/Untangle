// k6 load test (§15): the ticket list with filters, the busiest read in the app.
// Run: make load   (or see docs/scalability.md). Targets the stack through nginx.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://frontend:8080';
const RATE = Number(__ENV.RATE || 50); // requests per second
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    list: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:list}': ['p(95)<300', 'p(99)<800'],
  },
};

const FILTERS = [
  'status=OPEN&status=IN_PROGRESS',
  'priority=CRITICAL&priority=HIGH&sort=slaDueAt,asc',
  'unassigned=true',
  'slaBreached=true',
  'escalated=true&sort=priority,desc',
  'category=REFUND&q=refund',
  'q=card',
  'mine=true',
  '',
];

export function setup() {
  const res = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ email: 'leela.thomas@untangle.local', password: 'Untangle@2026' }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(res, { 'logged in': (r) => r.status === 200 });
  return { token: res.json('accessToken') };
}

export default function (data) {
  const filter = FILTERS[Math.floor(Math.random() * FILTERS.length)];
  const page = Math.floor(Math.random() * 3);
  const res = http.get(`${BASE}/api/v1/tickets?${filter}&page=${page}&size=20`, {
    headers: { Authorization: `Bearer ${data.token}` },
    tags: { endpoint: 'list' },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
