-- =============================================================================
-- V2: SLA clock start + sort ranks for tickets
-- =============================================================================

-- When the SLA clock last (re)started. sla_due_at = sla_started_at + target(priority).
-- Needed because a priority change recalculates the deadline from the SAME start, and a reopen
-- restarts the clock (R4). Existing rows start at their creation time.
ALTER TABLE tickets ADD COLUMN sla_started_at timestamptz;
UPDATE tickets SET sla_started_at = created_at;
ALTER TABLE tickets ALTER COLUMN sla_started_at SET NOT NULL;
ALTER TABLE tickets ALTER COLUMN sla_started_at SET DEFAULT now();

-- Severity order for sorting. Alphabetical order of the enum names is wrong
-- (CRITICAL < HIGH < LOW < MEDIUM), so we keep a numeric rank the database maintains itself.
ALTER TABLE tickets ADD COLUMN priority_rank smallint
    GENERATED ALWAYS AS (CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3
                                       WHEN 'MEDIUM' THEN 2 ELSE 1 END) STORED;

ALTER TABLE tickets ADD COLUMN status_rank smallint
    GENERATED ALWAYS AS (CASE status WHEN 'OPEN' THEN 1 WHEN 'IN_PROGRESS' THEN 2
                                     WHEN 'RESOLVED' THEN 3 ELSE 4 END) STORED;

-- Typical queue view: unresolved tickets, most severe first, oldest first within a severity.
CREATE INDEX ix_tickets_queue ON tickets (status_rank, priority_rank DESC, created_at);
