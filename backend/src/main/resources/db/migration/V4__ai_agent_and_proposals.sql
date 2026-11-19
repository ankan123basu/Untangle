-- =============================================================================
-- V4: an AI teammate (ADR-028)
--
-- Simple explanation: "Untangle AI" becomes a member of the team. Leads can assign a ticket to
-- it; it reads the ticket, writes a reply and proposes a resolution. A human lead approves the
-- proposal (and the ticket is resolved) or takes the ticket over. The AI never resolves, closes,
-- reassigns or reprioritises anything by itself, and never works CRITICAL tickets.
--
-- Technical details:
--   * agents.kind tells people (HUMAN) from the AI teammate (AI). The AI row is created by the
--     application at start-up (AiAgentAccount), not here, so test resets and fresh databases
--     always get it. It has no usable password and can never sign in.
--   * The AI is always a plain AGENT (no lead/admin powers) and there is at most one.
--   * ai_proposals keeps every proposal with model + prompt version (provenance) and who decided.
--     Only one PENDING proposal per ticket.
-- =============================================================================

ALTER TABLE agents ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'HUMAN';
ALTER TABLE agents ADD CONSTRAINT ck_agents_kind CHECK (kind IN ('HUMAN', 'AI'));
ALTER TABLE agents ADD CONSTRAINT ck_agents_ai_is_plain_agent CHECK (kind <> 'AI' OR role = 'AGENT');
CREATE UNIQUE INDEX ux_agents_single_ai ON agents (kind) WHERE kind = 'AI';

COMMENT ON COLUMN agents.kind IS 'HUMAN for people; AI for the single AI teammate (cannot sign in).';

CREATE TABLE ai_proposals (
    id                   uuid          PRIMARY KEY DEFAULT uuidv7(),
    ticket_id            uuid          NOT NULL REFERENCES tickets (id),
    reply                varchar(5000) NOT NULL,
    resolution_note      varchar(5000) NOT NULL,
    confidence           numeric(3, 2) NOT NULL,
    status               varchar(12)   NOT NULL DEFAULT 'PENDING',
    model                varchar(100)  NOT NULL,
    prompt_version       varchar(40)   NOT NULL,
    decided_by_agent_id  uuid          REFERENCES agents (id),
    decided_at           timestamptz,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    version              bigint        NOT NULL DEFAULT 0,

    CONSTRAINT ck_ai_proposals_status     CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT ck_ai_proposals_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_ai_proposals_reply      CHECK (length(btrim(reply)) >= 1),
    CONSTRAINT ck_ai_proposals_note       CHECK (length(btrim(resolution_note)) >= 1),
    CONSTRAINT ck_ai_proposals_decided
        CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by_agent_id IS NOT NULL AND decided_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_ai_proposals_one_pending ON ai_proposals (ticket_id) WHERE status = 'PENDING';
CREATE INDEX ix_ai_proposals_ticket ON ai_proposals (ticket_id, created_at DESC);

COMMENT ON TABLE ai_proposals IS 'Resolutions proposed by the AI teammate; a human approves or takes over (ADR-028).';

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type
    CHECK (type IN ('ASSIGNED', 'ESCALATED', 'SLA_BREACH', 'MENTION', 'REOPENED', 'AI_PROPOSAL', 'AI_HANDBACK'));
