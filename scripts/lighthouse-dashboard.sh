#!/usr/bin/env bash
# Lighthouse (mobile + desktop) on the signed-in dashboard of the local Docker stack.
# Uses the demo seed account, so run it against `make up` with the dev/demo profile only.
# Reports are written to $LH_OUT (default: a temp dir). Prints scores + failing audits.
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"
export CHROME_PATH="${CHROME_PATH:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
DIR="${LH_OUT:-$(mktemp -d)}"
URL="${1:-http://localhost:3000/}"

run() {
  local mode="$1"
  local jar="$DIR/jar.txt" headers="$DIR/headers.json"
  curl -s -c "$jar" -o /dev/null -H 'Content-Type: application/json' \
    -d '{"email":"admin@untangle.local","password":"Untangle@2026"}' http://localhost:3000/api/v1/auth/login
  local token
  token=$(awk '$6=="untangle_refresh"{print $7}' "$jar")
  printf '{"Cookie":"untangle_refresh=%s"}' "$token" > "$headers"
  local extra=()
  [[ "$mode" == "desktop" ]] && extra=(--preset=desktop)
  npx -y lighthouse@13.6.0 "$URL" ${extra[@]+"${extra[@]}"} --quiet \
    --only-categories=performance,accessibility,best-practices \
    --extra-headers="$headers" --chrome-flags="--headless=new" \
    --output=json --output-path="$DIR/$mode.json" >/dev/null 2>&1 || true
  rm -f "$jar" "$headers"
  node -e '
    const r = require(process.argv[1]);
    const s = Object.fromEntries(Object.entries(r.categories).map(([k, v]) => [k, Math.round(v.score * 100)]));
    const a = r.audits;
    console.log(process.argv[2], r.finalDisplayedUrl, JSON.stringify(s),
      "LCP", a["largest-contentful-paint"].displayValue, "CLS", a["cumulative-layout-shift"].displayValue,
      "TBT", a["total-blocking-time"].displayValue);
    for (const [id, au] of Object.entries(a)) {
      if (au.score !== null && au.score < 0.9 && au.scoreDisplayMode !== "informative" && au.scoreDisplayMode !== "manual" && au.scoreDisplayMode !== "notApplicable")
        console.log("  -", id, au.score, au.displayValue ?? "");
    }' "$DIR/$mode.json" "$mode"
}

run mobile
run desktop
