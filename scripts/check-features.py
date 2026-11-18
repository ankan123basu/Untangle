"""
End-to-end API walkthrough of every Untangle feature, plus a live role-by-endpoint RBAC matrix,
against the running stack (`make up`, demo profile). Run: `make check-features`.

It signs in with the demo accounts and creates its own data (tickets tagged "Feature check …",
an agent `check.<tag>@untangle.local` that ends deactivated, a partner `check-<tag>` that ends
disabled), so it can be re-run at any time. Exit code 1 if any check fails.
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("CHECK_BASE_URL", "http://localhost:3000") + "/api/v1"
PW = "Untangle@2026"
results = []


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode(errors="replace")


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def login(email):
    s, b = call("POST", "/auth/login", body={"email": email, "password": PW})
    assert s == 200, (email, s, b)
    return b["accessToken"], b["agent"]["id"]


admin, _ = login("admin@untangle.local")
lead, lead_id = login("leela.thomas@untangle.local")
agent, agent_id = login("riya.sen@untangle.local")
other, other_id = login("dev.malhotra@untangle.local")
check("Sign-in for admin, lead and two agents", True)

s, b = call("POST", "/auth/login", body={"email": "riya.sen@untangle.local", "password": "wrong"})
check("Wrong password → 401 AUTH_INVALID_CREDENTIALS", s == 401 and b.get("code") == "AUTH_INVALID_CREDENTIALS", s)

# ---- 1. Create (+ validation, valid priority rule)
tag = uuid.uuid4().hex[:6]
ticket = {"subject": f"Feature check {tag}", "description": "Card declined at the airport lounge.",
          "customerName": "Kiran Rao", "customerEmail": "kiran@example.com",
          "category": "ACCOUNT", "priority": "HIGH"}
s, t = call("POST", "/tickets", agent, ticket)
check("Create ticket (customer, description, category, priority)", s == 201 and t["ticketNumber"].startswith("UNT-"), f"{s} {t.get('ticketNumber') if isinstance(t, dict) else t}")
tid, version = t["id"], t["version"]
for bad, label in [({**ticket, "priority": "URGENT"}, "URGENT"), ({**ticket, "priority": None}, "null"),
                   ({**ticket, "priority": "high"}, "lowercase")]:
    s, b = call("POST", "/tickets", agent, bad)
    check(f"Invalid priority ({label}) rejected 400", s == 400 and b.get("code") == "VALIDATION_FAILED", s)
s, b = call("POST", "/tickets", agent, {**ticket, "description": "short"})
check("Too-short description rejected 400", s == 400, s)

# ---- 2. Assign
s, b = call("PUT", f"/tickets/{tid}/assignee", agent, {"agentId": other_id, "version": version})
check("Agent can't assign someone else (403)", s == 403, s)
s, t = call("PUT", f"/tickets/{tid}/assignee", lead, {"agentId": agent_id, "version": version})
check("Lead assigns ticket to an agent", s == 200 and t["assignee"]["id"] == agent_id, s)
version = t["version"]

# ---- optimistic locking
s, b = call("PATCH", f"/tickets/{tid}", lead, {"priority": "CRITICAL", "version": version - 1})
check("Stale version → 409 VERSION_CONFLICT", s == 409 and b.get("code") == "VERSION_CONFLICT", s)

# ---- 3. Status lifecycle
s, b = call("POST", f"/tickets/{tid}/transitions", agent, {"targetStatus": "RESOLVED", "version": version})
check("OPEN → RESOLVED directly is refused (409)", s == 409, s)
s, t = call("POST", f"/tickets/{tid}/transitions", agent, {"targetStatus": "IN_PROGRESS", "version": version})
check("OPEN → IN_PROGRESS", s == 200 and t["status"] == "IN_PROGRESS", s)
version = t["version"]

# ---- 4. Comments + resolution note
s, c = call("POST", f"/tickets/{tid}/comments", agent, {"body": "<b>Called</b> the bank; block lifted."})
check("Add comment (HTML stored as plain text)", s == 201 and c["body"].startswith("<b>"), s)
s, b = call("POST", f"/tickets/{tid}/transitions", agent, {"targetStatus": "RESOLVED", "version": version})
check("Resolve without a note refused", s in (400, 409), f"{s} {b.get('code') if isinstance(b, dict) else ''}")
s, t = call("POST", f"/tickets/{tid}/transitions", agent, {"targetStatus": "RESOLVED", "note": "Bank removed the block.", "version": version})
check("IN_PROGRESS → RESOLVED with resolution note", s == 200 and t["status"] == "RESOLVED", s)
version = t["version"]
s, tl = call("GET", f"/tickets/{tid}/comments", agent)
types = [e["type"] for e in tl]
check("Timeline has comment, resolution note and system events", {"COMMENT", "RESOLUTION_NOTE", "SYSTEM"} <= set(types), types)

s, b = call("POST", f"/tickets/{tid}/transitions", agent, {"targetStatus": "CLOSED", "version": version})
check("Agent can't close (403)", s == 403, s)
s, t = call("POST", f"/tickets/{tid}/transitions", lead, {"targetStatus": "CLOSED", "version": version})
check("Lead closes RESOLVED → CLOSED", s == 200 and t["status"] == "CLOSED", s)
version = t["version"]

# ---- Closed tickets can't be modified (business rule)
s, b = call("POST", f"/tickets/{tid}/comments", lead, {"body": "late comment"})
check("Comment on CLOSED ticket → 409 TICKET_CLOSED", s == 409 and b.get("code") == "TICKET_CLOSED", s)
s, b = call("PATCH", f"/tickets/{tid}", lead, {"priority": "LOW", "version": version})
check("Edit CLOSED ticket → 409 TICKET_CLOSED", s == 409 and b.get("code") == "TICKET_CLOSED", s)
s, b = call("PUT", f"/tickets/{tid}/assignee", lead, {"agentId": other_id, "version": version})
check("Reassign CLOSED ticket → 409 TICKET_CLOSED", s == 409 and b.get("code") == "TICKET_CLOSED", s)
s, b = call("POST", f"/tickets/{tid}/reopen", agent, {"reason": "customer came back", "version": version})
check("Agent can't reopen (403)", s == 403, s)
s, t = call("POST", f"/tickets/{tid}/reopen", lead, {"reason": "Customer says it happens again.", "version": version})
check("Lead reopens CLOSED → OPEN", s == 200 and t["status"] == "OPEN" and t["reopenedCount"] == 1, s)
version = t["version"]

# ---- Escalation + notification
s, t = call("POST", f"/tickets/{tid}/escalate", agent, {"reason": "VIP customer stuck abroad"})
check("Manual escalation raises the level", s == 200 and t["escalationLevel"] >= 1, s)
s, n = call("GET", "/notifications/unread-count", lead)
check("Lead has unread notifications (escalation/assignment)", s == 200 and n["count"] > 0, n)

# ---- 5. Filters
def total(q, tok=lead):
    s, b = call("GET", "/tickets?" + q, tok)
    assert s == 200, (q, s, b)
    return b["totalElements"], b["content"]

n, rows = total("priority=HIGH")
check("Filter by priority", n > 0 and all(r["priority"] == "HIGH" for r in rows), n)
n, rows = total(f"assigneeId={agent_id}")
check("Filter by assigned agent", n > 0 and all(r["assignee"] and r["assignee"]["id"] == agent_id for r in rows), n)
n, rows = total("status=OPEN&status=IN_PROGRESS")
check("Filter by status (multi)", n > 0 and all(r["status"] in ("OPEN", "IN_PROGRESS") for r in rows), n)
n, rows = total(f"q=Feature%20check%20{tag}")
check("Search by subject", n == 1, n)
s, b = call("GET", "/tickets?priority=URGENT", lead)
check("Unknown filter value → 400", s == 400, s)

# ---- 6. Dashboard
s, d = call("GET", "/dashboard/summary", lead)
unresolved_list, _ = total("status=OPEN&status=IN_PROGRESS")
check("Dashboard unresolved == OPEN+IN_PROGRESS list", s == 200 and d["unresolved"] == unresolved_list, f"{d.get('unresolved')} vs {unresolved_list}")
s, w = call("GET", "/dashboard/agents-workload", agent)
check("Agent can't see team workload (403)", s == 403, s)
s, w = call("GET", "/dashboard/agents-workload", lead)
check("Lead sees team workload", s == 200 and len(w) > 0, s)
s, tr = call("GET", "/dashboard/trends?days=14", lead)
check("14-day trend has 14 days", s == 200 and len(tr) == 14, len(tr) if isinstance(tr, list) else tr)

# ---- Admin CRUD: agents
email = f"check.{tag}@untangle.local"
s, a = call("POST", "/agents", lead, {"fullName": "Check Agent", "email": email, "password": "Temporary-Pass-1", "role": "AGENT"})
check("Lead can't create agents (403)", s == 403, s)
s, a = call("POST", "/agents", admin, {"fullName": "Check Agent", "email": email, "password": "Temporary-Pass-1", "role": "AGENT"})
check("Admin creates agent", s == 201, s)
new_id = a["id"]
s, b = call("POST", "/agents", admin, {"fullName": "Dup", "email": email.upper(), "password": "Temporary-Pass-1", "role": "AGENT"})
check("Duplicate email (any case) → 409 EMAIL_TAKEN", s == 409 and b.get("code") == "EMAIL_TAKEN", s)
s, a = call("PATCH", f"/agents/{new_id}", admin, {"role": "LEAD"})
check("Admin changes role", s == 200 and a["role"] == "LEAD", s)
s, a = call("PATCH", f"/agents/{new_id}/active", admin, {"active": False})
check("Admin deactivates agent", s == 200 and a["active"] is False, s)
s, b = call("POST", "/auth/login", body={"email": email, "password": "Temporary-Pass-1"})
check("Deactivated agent can't sign in", s in (401, 403, 423), f"{s} {b.get('code') if isinstance(b, dict) else ''}")

# ---- Admin: partners (secret shown once, rotation)
code = f"check-{tag}"
s, p = call("POST", "/admin/partners", admin, {"code": code, "name": "Check Bank", "rateLimitPerMinute": 60})
check("Admin creates partner, secret returned once", s == 201 and len(p.get("secret", "")) >= 32, s)
pid = p["partner"]["id"]
s, lst = call("GET", "/admin/partners", admin)
check("Partner list never contains secrets", s == 200 and "secret" not in json.dumps(lst).lower().replace("previoussecretvaliduntil", ""), s)
s, r = call("POST", f"/admin/partners/{pid}/rotate-secret", admin)
check("Rotate partner secret (24 h grace)", s == 200 and r["partner"]["previousSecretValidUntil"], s)
s, r = call("PATCH", f"/admin/partners/{pid}/active", admin, {"active": False})
check("Disable partner", s == 200 and r["active"] is False, s)
s, dl = call("GET", "/admin/webhook-deliveries?size=5", admin)
check("Webhook delivery log readable by admin", s == 200 and dl["totalElements"] > 0, s)
s, dl = call("GET", "/admin/webhook-deliveries", lead)
check("Lead can't read webhook logs (403)", s == 403, s)

# ---- AI (live Groq)
s, st = call("GET", "/ai/status", lead)
check("AI enabled", s == 200 and st["enabled"], st)
s, tri = call("POST", "/ai/triage", lead, {"subject": "Charged twice", "description": "I was charged two times for one order and want a refund."})
check("AI triage returns category + priority", s == 200 and tri["category"] and tri["priority"], f"{tri.get('category')}/{tri.get('priority')}" if isinstance(tri, dict) else tri)
s, sm = call("POST", f"/tickets/{tid}/ai/summary", lead)
check("AI summary of the test ticket", s == 200 and len(sm.get("summary", "")) > 20, s)
s, dr = call("POST", f"/tickets/{tid}/ai/reply-draft", lead)
check("AI resolution-note draft", s == 200 and len(dr.get("draft", "")) > 20, s)


# ---- RBAC matrix: every role against every protected action -----------------------------------
ROLES = {"AGENT": agent, "LEAD": lead, "ADMIN": admin}


def fresh_ticket(owner_token, assignee=None):
    body = {**ticket, "subject": f"RBAC check {uuid.uuid4().hex[:6]}"}
    if assignee:
        body["assigneeId"] = assignee
    s, t = call("POST", "/tickets", owner_token, body)
    assert s == 201, (s, t)
    return t


def other_persons_ticket():
    # Assigned to Dev (another agent), so "own" permissions don't apply to Riya.
    return fresh_ticket(lead, other_id)


cases = [
    # (action, expected outcome per role AGENT/LEAD/ADMIN, request builder)
    ("List tickets", "✅✅✅", lambda tok: call("GET", "/tickets?size=1", tok)),
    ("View dashboard", "✅✅✅", lambda tok: call("GET", "/dashboard/summary", tok)),
    ("Create ticket", "✅✅✅", lambda tok: call("POST", "/tickets", tok, ticket)),
    ("Use AI (status)", "✅✅✅", lambda tok: call("GET", "/ai/status", tok)),
    ("Comment on another agent's ticket", "✅✅✅",
     lambda tok: call("POST", f"/tickets/{other_persons_ticket()['id']}/comments", tok, {"body": "rbac"})),
    ("Escalate", "✅✅✅",
     lambda tok: call("POST", f"/tickets/{other_persons_ticket()['id']}/escalate", tok, {"reason": "rbac check"})),
    ("Assign ticket to someone else", "❌✅✅",
     lambda tok: (lambda t: call("PUT", f"/tickets/{t['id']}/assignee", tok, {"agentId": lead_id, "version": t["version"]}))(fresh_ticket(lead))),
    ("Edit another agent's ticket", "❌✅✅",
     lambda tok: (lambda t: call("PATCH", f"/tickets/{t['id']}", tok, {"priority": "LOW", "version": t["version"]}))(other_persons_ticket())),
    ("Start progress on another agent's ticket", "❌✅✅",
     lambda tok: (lambda t: call("POST", f"/tickets/{t['id']}/transitions", tok, {"targetStatus": "IN_PROGRESS", "version": t["version"]}))(other_persons_ticket())),
    ("Close an open ticket (with reason)", "❌✅✅",
     lambda tok: (lambda t: call("POST", f"/tickets/{t['id']}/transitions", tok, {"targetStatus": "CLOSED", "reason": "duplicate", "version": t["version"]}))(fresh_ticket(lead))),
    ("Bulk assign", "❌✅✅",
     lambda tok: call("POST", "/tickets/bulk/assign", tok, {"ticketIds": [fresh_ticket(lead)["id"]], "agentId": agent_id})),
    ("See team workload", "❌✅✅", lambda tok: call("GET", "/dashboard/agents-workload", tok)),
    ("List agents (admin view)", "❌❌✅", lambda tok: call("GET", "/admin/agents", tok)),
    ("Create agent", "❌❌✅", lambda tok: create_probe_agent(tok)),
    ("Manage partners", "❌❌✅", lambda tok: call("GET", "/admin/partners", tok)),
    ("Read webhook logs", "❌❌✅", lambda tok: call("GET", "/admin/webhook-deliveries?size=1", tok)),
]

def create_probe_agent(tok):
    """Tries to create an agent; if it works, deactivates it at once so the demo data stays clean."""
    s, a = call("POST", "/agents", tok, {"fullName": "Rbac Probe", "email": f"rbac.{uuid.uuid4().hex[:6]}@untangle.local", "password": "Temporary-Pass-1", "role": "AGENT"})
    if s == 201:
        call("PATCH", f"/agents/{a['id']}/active", admin, {"active": False})
    return s, a


matrix_rows = []
for action, expected, request in cases:
    marks = []
    for (role, tok), want in zip(ROLES.items(), expected):
        status, _ = request(tok)
        allowed = 200 <= status < 300
        ok = allowed == (want == "✅")
        marks.append(("✅" if allowed else "❌") + ("" if ok else f"!({status})"))
        check(f"RBAC {role:<5} {action}", ok, status)
    matrix_rows.append((action, marks))
for path in ["/tickets", "/dashboard/summary", "/admin/agents"]:
    s, _ = call("GET", path)
    check(f"RBAC anonymous {path} → 401", s == 401, s)
# Deactivated accounts lose access on the next request (agent created above).

# ---- Errors are Problem Details
s, b = call("GET", "/tickets/00000000-0000-7000-8000-000000000000", lead)
check("Unknown ticket → 404 Problem Details with traceId", s == 404 and b.get("code") and b.get("traceId"), s)
s, b = call("GET", "/tickets")
check("No token → 401 Problem Details", s == 401 and b.get("code"), s)

width = max(len(n) for n, _, _ in results)
for name, ok, detail in results:
    print(("PASS " if ok else "FAIL ") + name.ljust(width) + ("" if ok else f"   [{detail}]"))
print("\nRBAC matrix (live)        AGENT LEAD ADMIN")
for action, marks in matrix_rows:
    print(f"  {action:<40} " + "  ".join(f"{m:<4}" for m in marks))
passed = sum(ok for _, ok, _ in results)
print(f"\n{passed}/{len(results)} checks passed")
sys.exit(0 if passed == len(results) else 1)
