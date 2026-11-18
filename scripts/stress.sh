#!/usr/bin/env bash
# Stress test on a SEPARATE copy of the app (project `untangle-stress`, ports 3100/8180/5435) filled
# with 200,000 tickets, so your own data is never touched. Runs the mixed k6 test at increasing
# request rates and prints latency, errors and CPU/memory per step. Usage:
#   make stress                    # default ladder
#   RATES="100 300 500" make stress
#   make stress-down               # remove the stress copy and its data
set -euo pipefail
cd "$(dirname "$0")/.."
export FRONTEND_HOST_PORT=3100 BACKEND_HOST_PORT=8180 POSTGRES_HOST_PORT=5435 AI_ENABLED=false
COMPOSE="docker compose -p untangle-stress"
OUT=load/results
mkdir -p "$OUT"

$COMPOSE up -d --no-build postgres backend frontend >/dev/null
for _ in $(seq 1 90); do curl -sf localhost:8180/readyz >/dev/null && break; sleep 2; done

count=$($COMPOSE exec -T postgres psql -U untangle -d untangle -tAc "select count(*) from tickets")
if [ "$count" -lt 100000 ]; then
  echo "Loading 200,000 tickets into the stress database (about 30 s)..."
  $COMPOSE exec -T postgres psql -q -U untangle -d untangle -v ON_ERROR_STOP=1 < load/stress-data.sql >/dev/null
fi

for R in ${RATES:-100 200 300 400 500 600}; do
  ( sleep 18; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' \
      untangle-stress-backend-1 untangle-stress-postgres-1 > "$OUT/stats-$R.txt" ) &
  docker run --rm --network untangle-stress_default -v "$PWD/load/k6:/scripts:ro" -v "$PWD/$OUT:/out" \
    grafana/k6:1.8.2 run -q /scripts/stress-mixed.js -e BASE_URL=http://frontend:8080 -e RATE="$R" \
    -e DURATION=30s --summary-export "/out/summary-$R.json" > "$OUT/k6-$R.txt" 2>&1 || true
  wait
  python3 - "$OUT" "$R" <<'PY'
import json, sys
out, r = sys.argv[1], sys.argv[2]
m = json.load(open(f"{out}/summary-{r}.json"))["metrics"]
d, f, n = m["http_req_duration"], m["http_req_failed"], m["http_reqs"]
stats = " | ".join(l.strip().replace("untangle-stress-", "") for l in open(f"{out}/stats-{r}.txt"))
print(f"target {r:>5}/s  achieved {n['rate']:6.1f}/s  p50 {d['med']:8.1f}ms  p95 {d['p(95)']:8.1f}ms"
      f"  errors {f['value']*100:5.2f}%  || {stats}", flush=True)
PY
  sleep 5
done
