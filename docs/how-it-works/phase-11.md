# How it works — Phase 11: Documentation and demo

## Simple explanation

The project now explains itself: a README that covers what Untangle does, how it is built and why,
a step-by-step manual test guide, a 10-minute demo script, and finished design documents. Every
claim in them was checked against the running app.

## Technical details

| Deliverable | Where |
|---|---|
| Project overview: features, requirement coverage, screenshots, HLD, flows, security, ADRs, trade-offs, performance, testing, commands | [README.md](../../README.md) |
| Manual test guide (every feature, role and CRUD path) | [manual-test-guide.md](../manual-test-guide.md) |
| 10-minute demo script | [demo-script.md](../demo-script.md) |
| Trade-offs (23) and scalability (built, measured, 10×/100× plan) | [tradeoffs.md](../tradeoffs.md), [scalability.md](../scalability.md) |
| Architecture with sequence diagrams; observability; security; runbook | `docs/` |
| Screenshots | `docs/images/` |

Final verification on the running demo stack:

| Check | Result |
|---|---|
| `make ci` | 404 backend + 56 frontend tests, coverage gate met, build OK |
| `make e2e` | 9/9 |
| `make check-features` | 103/103, including the live RBAC matrix |
| `make simulate` | 13/13 |
| AI with a real Groq key | triage, summary and note draft all valid |

Polish done in this phase from review feedback: clearer login page, phone layouts for every screen,
a richer 3D login story, a designed dark theme (charcoal hero, glowing yellow strand), theme toggle
on the login page, and a fix for the command palette's focus.
