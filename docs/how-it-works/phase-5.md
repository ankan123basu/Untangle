# How it works — Phase 5: SLA escalation and notifications

## Simple explanation

- Every ticket has a deadline. **Once a minute** the system looks for tickets that are still open
  past their deadline and raises an **alarm level**: level 1 at the deadline, level 2 if it's twice
  as late, level 3 at three times (the maximum). Each step is written on the ticket's timeline.
- Team leads and the ticket's owner get a **notification** (the bell in the app) when that happens,
  when someone escalates a ticket by hand, when a ticket is assigned to them, and when one of their
  tickets is reopened. You're never notified about something you did yourself.
- If several copies of the backend are running, only **one** of them does the minute check, and even
  if two did, no ticket would be escalated twice.
- Webhook logs older than 30 days are deleted automatically each night.

## Technical details

### 1. The scan (`SlaBreachProcessor.escalateBreaches(now, maxLevel)`)

```
@Scheduled(cron "0 * * * * *") + @SchedulerLock("sla-escalation")      ← one instance per minute
  └─ @Transactional
     SELECT ... FROM tickets WHERE status IN (OPEN, IN_PROGRESS) AND sla_due_at < now
       ORDER BY sla_due_at FOR UPDATE  LIMIT 200 OFFSET ...            ← partial index; rows locked
     for each: due = min(maxLevel, floor((now − sla_started_at) / target(priority)))
               ticket.escalateToAtLeast(due)  → only raises, never repeats
               timeline SYSTEM {event: SLA_BREACH, level}; publish TicketEscalated(automatic)
  commit → NotificationService.onEscalated (AFTER_COMMIT, REQUIRES_NEW) → leads + assignee
```

Why it's safe three times over: ShedLock (one runner), `FOR UPDATE` (a concurrent runner waits),
and the monotonic `escalateToAtLeast` (re-runs are no-ops). The test
`twoConcurrentRunsEscalateEachTicketOnce` runs two scans in parallel threads with ShedLock bypassed,
to prove the last two layers alone are enough.

### 2. Notifications

`NotificationService` listens to `TicketAssigned`, `TicketEscalated` and `TicketReopened` **after
commit** in a new transaction, so a rolled-back change notifies nobody. Recipients are de-duplicated,
and the actor is excluded. The SPA polls `GET /notifications/unread-count` every 30 s (ADR-021).

### 3. Verified

| Check | Result |
|---|---|
| `./mvnw verify` | **372 tests** pass (+9) |
| Concurrency | 2 parallel scans → each ticket escalated exactly once |

### Questions your lead might ask

- **"Why not a delayed message per ticket?"** Priority edits and reopens would have to reschedule
  messages; a time-based scan computes the right level from current data every time, and it needs
  no extra infrastructure.
- **"Can a manual escalation be undone by the scheduler?"** No: the scheduler only raises levels.
