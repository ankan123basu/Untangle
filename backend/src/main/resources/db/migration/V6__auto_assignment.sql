-- =============================================================================
-- V6: automatic ticket assignment (ADR-029)
--
-- Simple explanation: when auto-assign is switched on, new and waiting tickets are handed to the
-- least busy agent who handles that kind of ticket, most urgent ticket first. Leads choose who
-- takes automatic work and which categories each person handles. An agent who gives a ticket back
-- is not handed the same ticket again.
--
-- Technical details:
--   * auto_assign_settings: one row (unique index on a constant). Missing row = switched off.
--   * agent_routing: per-agent opt-in and the time of their last automatic assignment (fair
--     tie-break). No row = defaults (human AGENTs take work, leads/admins/the AI don't).
--   * agent_routing_skills: the categories an agent handles; none = every category.
--   * tickets.released_by_agent_id: who last gave the ticket back (release or AI hand-back), so the
--     planner never hands it straight back to them.
--   * ix_tickets_unassigned_queue: the queue the planner walks (OPEN, unassigned, by SLA due).
-- =============================================================================

CREATE TABLE auto_assign_settings (
    id                  uuid        PRIMARY KEY DEFAULT uuidv7(),
    enabled             boolean     NOT NULL DEFAULT false,
    max_open_per_agent  integer     NOT NULL DEFAULT 10,
    updated_by_agent_id uuid        REFERENCES agents (id) ON DELETE RESTRICT,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             bigint      NOT NULL DEFAULT 0,

    CONSTRAINT ck_auto_assign_settings_cap CHECK (max_open_per_agent BETWEEN 1 AND 100)
);

CREATE UNIQUE INDEX ux_auto_assign_settings_single ON auto_assign_settings ((true));

COMMENT ON TABLE auto_assign_settings IS 'Single row: is automatic assignment on, and the per-agent cap.';

CREATE TABLE agent_routing (
    id                    uuid        PRIMARY KEY DEFAULT uuidv7(),
    agent_id              uuid        NOT NULL REFERENCES agents (id) ON DELETE RESTRICT,
    auto_assign           boolean     NOT NULL,
    last_auto_assigned_at timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    version               bigint      NOT NULL DEFAULT 0,

    CONSTRAINT uq_agent_routing_agent UNIQUE (agent_id)
);

CREATE TABLE agent_routing_skills (
    routing_id uuid        NOT NULL REFERENCES agent_routing (id) ON DELETE CASCADE,
    category   varchar(20) NOT NULL,

    PRIMARY KEY (routing_id, category),
    CONSTRAINT ck_agent_routing_skills_category
        CHECK (category IN ('BILLING', 'TECHNICAL', 'ACCOUNT', 'REFUND', 'GENERAL', 'OTHER'))
);

ALTER TABLE tickets
    ADD COLUMN released_by_agent_id uuid REFERENCES agents (id) ON DELETE RESTRICT;

CREATE INDEX ix_tickets_unassigned_queue
    ON tickets (sla_due_at, id)
    WHERE status = 'OPEN' AND assigned_agent_id IS NULL;
