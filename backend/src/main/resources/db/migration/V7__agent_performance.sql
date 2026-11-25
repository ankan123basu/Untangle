-- =============================================================================
-- V7: agent performance stats (ADR-030)
--
-- Simple explanation: every time a ticket is resolved we write down one fact row: who owned it,
-- when they got it, when the customer first asked, when it was resolved, and whether that was
-- inside the SLA. If the ticket later comes back (reopened, or a resolution is undone) the row is
-- marked, which gives the "reopen rate". The performance screen is a GROUP BY over these rows.
--
-- Technical details:
--   * tickets.assigned_at: when the current assignee got the ticket (null when unassigned). Lets us
--     measure "handle time" (assigned -> resolved) separately from the customer's wait.
--   * ticket_resolutions: one row per RESOLVE. Credit goes to the assignee at that moment (a lead
--     approving Unty's proposal credits Unty; resolved_by_agent_id records the lead).
--     Insert-only, except reopened_at, which is set once.
--   * At most one "standing" resolution per ticket (partial unique index), which also makes the
--     demo seed's ON CONFLICT DO NOTHING skip rows this migration already backfilled.
--   * Backfill: assigned_at from the latest ASSIGNED timeline event (else the SLA start); one
--     resolution row for every ticket that is RESOLVED or CLOSED with a resolved_at and an owner.
-- =============================================================================

ALTER TABLE tickets ADD COLUMN assigned_at timestamptz;

COMMENT ON COLUMN tickets.assigned_at IS 'When the current assignee got the ticket; null when unassigned.';

UPDATE tickets t
SET assigned_at = coalesce(
        (SELECT max(c.created_at)
         FROM ticket_comments c
         WHERE c.ticket_id = t.id
           AND c.type = 'SYSTEM'
           AND c.metadata ->> 'event' = 'ASSIGNED'
           AND c.metadata ->> 'to' = t.assigned_agent_id::text),
        t.sla_started_at)
WHERE t.assigned_agent_id IS NOT NULL;

CREATE TABLE ticket_resolutions (
    id                   uuid        PRIMARY KEY DEFAULT uuidv7(),
    ticket_id            uuid        NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    -- Who gets the credit: the assignee when the ticket was resolved.
    agent_id             uuid        NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    -- Who pressed the button (the same agent, a lead, or a lead approving Unty's proposal).
    resolved_by_agent_id uuid        REFERENCES agents (id) ON DELETE RESTRICT,
    priority             varchar(10) NOT NULL,
    category             varchar(20) NOT NULL,
    opened_at            timestamptz NOT NULL,
    assigned_at          timestamptz NOT NULL,
    resolved_at          timestamptz NOT NULL,
    sla_due_at           timestamptz NOT NULL,
    reopened_at          timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_resolutions_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_resolutions_category CHECK (category IN ('BILLING', 'TECHNICAL', 'ACCOUNT', 'REFUND', 'GENERAL', 'OTHER')),
    CONSTRAINT ck_resolutions_order    CHECK (opened_at <= assigned_at AND assigned_at <= resolved_at),
    CONSTRAINT ck_resolutions_reopened CHECK (reopened_at IS NULL OR reopened_at >= resolved_at)
);

-- A ticket has at most one resolution that still stands.
CREATE UNIQUE INDEX ux_ticket_resolutions_standing
    ON ticket_resolutions (ticket_id)
    WHERE reopened_at IS NULL;
-- The performance query: resolutions in a period, per agent.
CREATE INDEX ix_ticket_resolutions_resolved_agent ON ticket_resolutions (resolved_at, agent_id);

COMMENT ON TABLE ticket_resolutions IS 'One row per RESOLVE, credited to the assignee; feeds agent performance stats (ADR-030).';

INSERT INTO ticket_resolutions (ticket_id, agent_id, priority, category, opened_at, assigned_at,
                                resolved_at, sla_due_at)
SELECT t.id,
       t.assigned_agent_id,
       t.priority,
       t.category,
       least(t.sla_started_at, t.resolved_at),
       greatest(least(t.sla_started_at, t.resolved_at), least(coalesce(t.assigned_at, t.sla_started_at), t.resolved_at)),
       t.resolved_at,
       t.sla_due_at
FROM tickets t
WHERE t.status IN ('RESOLVED', 'CLOSED')
  AND t.resolved_at IS NOT NULL
  AND t.assigned_agent_id IS NOT NULL;
