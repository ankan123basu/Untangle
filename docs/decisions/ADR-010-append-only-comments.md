# ADR-010: Ticket comments are an append-only audit trail

- **Status:** Accepted
- **Date:** 2026-11-18
- **Phase:** 1

## Context

The timeline holds human comments, resolution notes and system events (status changes, assignments,
escalations). In a fintech support context it is effectively the **audit log** of what happened to
a complaint and who did it. Editable history can't be trusted.

## Options considered

1. **Editable comments with an `edited_at` flag.** Familiar UX, but the original text is lost.
2. **Editable with a separate revisions table.** Full history, but more schema and UI complexity.
3. **Append-only.** Corrections are made by adding a new comment.

## Decision

Option 3, enforced at two layers:
- **Java:** `TicketComment` is `@Immutable` (Hibernate never issues UPDATEs), all columns are
  `updatable = false`, and there are no setters.
- **Database:** trigger `trg_ticket_comments_append_only` rejects any `UPDATE` with a
  `check_violation`, even from psql or a future buggy query.

The same pattern (`@Immutable`) applies to `webhook_deliveries`.

## Consequences

- **Pros:** a trustworthy history; simpler concurrency (nothing to lock on comments, so they need no
  `version`); easy to explain to auditors.
- **Cons:** typos stay visible. If editing is ever required, add a revisions table (option 2) rather
  than relaxing the trigger.
- **Test:** `SchemaConstraintsIntegrationTest.commentsCannotBeEdited`.

## Simple explanation

Once something is written on a ticket's timeline, it stays exactly as written, like a logbook. To
correct something, you add a new note.
