-- =============================================================================
-- R__demo_data.sql — realistic demo data (profile `demo`, included by `dev`)
--
-- * Repeatable migration: Flyway re-runs it whenever this file changes.
-- * Idempotent: fixed ids (md5 of a stable key) + ON CONFLICT DO NOTHING, so a
--   re-run never duplicates rows.
-- * Timestamps are relative to now(), so the dashboard looks "live" whenever the
--   data is first loaded.
-- * Every row satisfies the V1 constraints; the demo seed test proves it.
--
-- Demo password for EVERY account below: Untangle@2026   (BCrypt, cost 12)
-- NEVER enable the `demo` profile in a real production environment.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Staff: 1 admin, 2 leads, 6 agents
-- -----------------------------------------------------------------------------
INSERT INTO agents (id, full_name, email, password_hash, role, last_login_at, created_at, updated_at)
SELECT md5('agent:' || email)::uuid, full_name, email,
       '$2a$12$B2QhF59h5Q6VTYJtahrkTux2F2OGYcsROLS4oYPEct0wbWDRVVZXy',
       role, now() - interval '2 hours', now() - interval '60 days', now() - interval '60 days'
FROM (VALUES
    ('Asha Verma',     'admin@untangle.local',          'ADMIN'),
    ('Leela Thomas',   'leela.thomas@untangle.local',   'LEAD'),
    ('Nikhil Bose',    'nikhil.bose@untangle.local',    'LEAD'),
    ('Riya Sen',       'riya.sen@untangle.local',       'AGENT'),
    ('Dev Malhotra',   'dev.malhotra@untangle.local',   'AGENT'),
    ('Zoya Ali',       'zoya.ali@untangle.local',       'AGENT'),
    ('Harish Nair',    'harish.nair@untangle.local',    'AGENT'),
    ('Tanvi Shah',     'tanvi.shah@untangle.local',     'AGENT'),
    ('Omar Siddiqui',  'omar.siddiqui@untangle.local',  'AGENT')
) AS staff(full_name, email, role)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Partner: one demo partner system.
-- The secret is a placeholder until Phase 4 introduces AES-GCM encryption and a
-- dev key; Phase 4 replaces this value (the simulator needs a real secret).
-- -----------------------------------------------------------------------------
INSERT INTO partners (id, code, name, secret_ciphertext, rate_limit_per_minute, created_at, updated_at)
VALUES (md5('partner:acme-bank')::uuid, 'acme-bank', 'Acme Bank (demo partner)',
        'placeholder:replaced-in-phase-4', 120, now() - interval '30 days', now() - interval '30 days')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Tickets: 60, newest first by n (n = 1 is the most recent).
--   Status by age: recent tickets are mostly OPEN / IN_PROGRESS, older ones are
--   RESOLVED / CLOSED, with a few old OPEN ones that have breached their SLA.
--   Totals: OPEN 15, IN_PROGRESS 10, RESOLVED 13, CLOSED 22.
--   Every 4th ticket arrived through the partner webhook.
-- -----------------------------------------------------------------------------
WITH templates (i, subject, description, category) AS (VALUES
    (1,  'Refund not received after cancellation',      'I cancelled my order 7 days ago and the refund still has not reached my account.',              'REFUND'),
    (2,  'Charged twice for the same transaction',      'My card statement shows two identical debits of Rs 2,499 for a single purchase yesterday.',     'BILLING'),
    (3,  'Unable to log in after password reset',       'I reset my password but the app keeps saying my credentials are invalid.',                    'ACCOUNT'),
    (4,  'UPI payment stuck in pending',                'A UPI payment I made 3 hours ago is still pending, but the money has left my bank account.',   'TECHNICAL'),
    (5,  'Late fee applied although I paid on time',    'I paid before the due date but a late fee was still added to this month''s statement.',         'BILLING'),
    (6,  'App crashes when opening statements',         'The mobile app closes immediately every time I tap on the Statements tab.',                    'TECHNICAL'),
    (7,  'Update registered mobile number',             'I changed my phone number and need to update it on my account so I can receive OTPs.',         'ACCOUNT'),
    (8,  'Refund amount is less than expected',         'I received a refund but it is Rs 300 less than the order amount, with no explanation.',        'REFUND'),
    (9,  'OTP not arriving on my phone',                'I am not receiving any OTPs, so I cannot complete transactions or log in.',                     'TECHNICAL'),
    (10, 'Card blocked while travelling abroad',        'My card was blocked when I tried to pay at a hotel abroad and I am stuck without funds.',       'ACCOUNT'),
    (11, 'When do my reward points expire?',            'I want to know when my reward points expire and how to redeem them before that happens.',      'GENERAL'),
    (12, 'EMI conversion not reflected',                'I converted a purchase to EMI last week but the full amount is still showing as due.',         'BILLING'),
    (13, 'KYC verification rejected without reason',    'My KYC documents were rejected but the app does not say what is wrong with them.',            'ACCOUNT'),
    (14, 'Cashback not credited for offer',             'I completed all the offer conditions but the promised cashback has not been credited.',       'REFUND'),
    (15, 'Unable to download tax statement',            'The download button for my annual tax statement shows an error and nothing downloads.',      'TECHNICAL'),
    (16, 'Transaction search is hard to find',          'The new app design looks nice, but the transaction search is now hard to find.',             'GENERAL'),
    (17, 'Merchant refund pending for 15 days',         'The merchant confirmed they issued the refund 15 days ago but I still do not see it.',        'REFUND'),
    (18, 'Auto-debit failed despite sufficient balance','My auto-debit for the loan EMI failed even though my account had enough balance.',            'BILLING'),
    (19, 'Login alert from an unknown device',          'I received an alert about a login from a device I do not recognise and I am worried.',        'ACCOUNT'),
    (20, 'Unknown merchant on my statement',            'There is a transaction from a merchant I have never heard of on my latest statement.',       'OTHER')
),
customers (i, name) AS (VALUES
    (1, 'Rahul Sharma'), (2, 'Priya Nair'), (3, 'Arjun Mehta'), (4, 'Sneha Iyer'), (5, 'Vikram Singh'),
    (6, 'Ananya Gupta'), (7, 'Karthik Reddy'), (8, 'Fatima Sheikh'), (9, 'Rohan Das'), (10, 'Meera Pillai'),
    (11, 'Aditya Joshi'), (12, 'Kavya Menon'), (13, 'Siddharth Rao'), (14, 'Neha Kapoor'), (15, 'Imran Khan'),
    (16, 'Divya Krishnan'), (17, 'Manish Patel'), (18, 'Aisha Fernandes'), (19, 'Rajesh Kumar'), (20, 'Pooja Bansal')
),
staff AS (
    SELECT id, row_number() OVER (ORDER BY email) AS i
    FROM agents
    WHERE role = 'AGENT' AND email LIKE '%@untangle.local'
),
base AS (
    SELECT n,
           61 - n AS num,
           -- Spread over ~10 days, denser for recent tickets (like a real queue).
           now() - make_interval(mins => round(6 * power(n, 1.9))::int) AS created_at,
           CASE WHEN n % 10 = 0 THEN 'CRITICAL'
                WHEN n % 10 IN (1, 4, 7) THEN 'HIGH'
                WHEN n % 10 IN (3, 8) THEN 'LOW'
                ELSE 'MEDIUM' END AS priority,
           CASE WHEN n <= 14 THEN CASE WHEN n % 3 = 0 THEN 'IN_PROGRESS' ELSE 'OPEN' END
                WHEN n <= 30 THEN CASE n % 3 WHEN 0 THEN 'IN_PROGRESS' WHEN 1 THEN 'RESOLVED' ELSE 'OPEN' END
                ELSE CASE WHEN n % 4 = 3 THEN 'RESOLVED' ELSE 'CLOSED' END END AS status,
           n % 4 = 1 AS from_webhook
    FROM generate_series(1, 60) AS n
),
with_sla AS (
    SELECT b.*,
           b.created_at + CASE b.priority WHEN 'CRITICAL' THEN interval '1 hour'
                                          WHEN 'HIGH'     THEN interval '4 hours'
                                          WHEN 'MEDIUM'   THEN interval '24 hours'
                                          ELSE                 interval '72 hours' END AS sla_due_at
    FROM base b
),
with_times AS (
    SELECT s.*,
           CASE WHEN s.status <> 'OPEN' THEN s.created_at + interval '18 minutes' END AS first_response_at,
           CASE WHEN s.status IN ('RESOLVED', 'CLOSED')
                THEN least(s.created_at + (s.sla_due_at - s.created_at) * (0.5 + (s.n % 3) * 0.4),
                           now() - interval '10 minutes') END AS resolved_at
    FROM with_sla s
),
final AS (
    SELECT t.*,
           CASE WHEN t.status = 'CLOSED'
                THEN least(t.resolved_at + interval '6 hours', now() - interval '5 minutes') END AS closed_at
    FROM with_times t
)
INSERT INTO tickets (id, ticket_number, subject, description, customer_name, customer_email,
                     category, priority, status, assigned_agent_id, assigned_at, created_by_agent_id, source,
                     partner_id, external_event_id, escalation_level, sla_started_at, sla_due_at,
                     first_response_at, resolved_at, closed_at, reopened_count, created_at, updated_at)
SELECT md5('ticket:' || f.n)::uuid,
       'UNT-' || lpad(f.num::text, 6, '0'),
       tpl.subject,
       tpl.description,
       c.name,
       lower(replace(c.name, ' ', '.')) || '@example.com',
       tpl.category,
       f.priority,
       f.status,
       CASE WHEN f.status = 'OPEN' AND f.n % 2 = 1 THEN NULL ELSE assignee.id END,
       CASE WHEN f.status = 'OPEN' AND f.n % 2 = 1 THEN NULL
            ELSE least(f.created_at + interval '4 minutes', now()) END,
       CASE WHEN f.from_webhook THEN NULL ELSE creator.id END,
       CASE WHEN f.from_webhook THEN 'WEBHOOK' ELSE 'MANUAL' END,
       CASE WHEN f.from_webhook THEN md5('partner:acme-bank')::uuid END,
       CASE WHEN f.from_webhook THEN 'evt_demo_' || lpad(f.n::text, 4, '0') END,
       CASE WHEN f.status IN ('OPEN', 'IN_PROGRESS') AND f.sla_due_at < now()
            THEN 1 + (f.n % 7 = 0)::int ELSE 0 END,
       f.created_at,
       f.sla_due_at,
       f.first_response_at,
       f.resolved_at,
       f.closed_at,
       CASE WHEN f.n IN (41, 52) THEN 1 ELSE 0 END,
       f.created_at,
       coalesce(f.closed_at, f.resolved_at, f.first_response_at, f.created_at)
FROM final f
JOIN templates tpl ON tpl.i = ((f.n - 1) % 20) + 1
JOIN customers c   ON c.i   = ((f.n * 7) % 20) + 1
JOIN staff assignee ON assignee.i = (f.n % 6) + 1
JOIN staff creator  ON creator.i  = ((f.n + 2) % 6) + 1
ON CONFLICT DO NOTHING;

-- Resolutions (ADR-030): one standing row per RESOLVED/CLOSED ticket, credited to the assignee,
-- plus an earlier resolution that didn't stick for the two reopened tickets (n = 41, 52).
-- Agents resolve at different speeds (n % 3 drives both the assignee and the resolve time), so
-- the performance screen shows real differences.
INSERT INTO ticket_resolutions (id, ticket_id, agent_id, resolved_by_agent_id, priority, category,
                                opened_at, assigned_at, resolved_at, sla_due_at, reopened_at, created_at)
SELECT md5('resolution:' || n || ':' || k)::uuid, t.id, t.assigned_agent_id, t.assigned_agent_id,
       t.priority, t.category, t.sla_started_at,
       least(t.assigned_at, r.resolved_at),
       r.resolved_at, t.sla_due_at, r.reopened_at, r.resolved_at
FROM generate_series(1, 60) AS n
JOIN tickets t ON t.id = md5('ticket:' || n)::uuid
CROSS JOIN LATERAL (VALUES
    (2, t.resolved_at, NULL::timestamptz),
    (1, t.sla_started_at + (t.resolved_at - t.sla_started_at) * 0.4,
        t.sla_started_at + (t.resolved_at - t.sla_started_at) * 0.6)
) AS r (k, resolved_at, reopened_at)
WHERE t.status IN ('RESOLVED', 'CLOSED') AND t.resolved_at IS NOT NULL
  AND t.assigned_agent_id IS NOT NULL AND t.assigned_at IS NOT NULL
  AND (r.k = 2 OR n IN (41, 52))
ON CONFLICT DO NOTHING;

-- Keep the sequence ahead of seeded numbers so new tickets continue at UNT-000061.
SELECT setval('ticket_number_seq', greatest((SELECT last_value FROM ticket_number_seq), 60));

-- -----------------------------------------------------------------------------
-- Timelines: agent comments, resolution notes and system events
-- -----------------------------------------------------------------------------
WITH seeded AS (
    SELECT n, t.*
    FROM generate_series(1, 60) AS n
    JOIN tickets t ON t.id = md5('ticket:' || n)::uuid
),
agent_lines (i, body) AS (VALUES
    (0, 'Hi, I have picked this up and I am checking with the payments team now.'),
    (1, 'Called the customer and confirmed the details. Raising it with the operations desk.'),
    (2, 'Verified the transaction reference in the ledger. Waiting for the bank''s confirmation.'),
    (3, 'Asked the customer for a screenshot of the error and the app version.'),
    (4, 'Escalated internally to tier 2, as this needs a manual ledger correction.')
),
resolution_lines (i, body) AS (VALUES
    (0, 'Refund reprocessed via NEFT. The customer confirmed receipt of the full amount.'),
    (1, 'Duplicate debit reversed. A confirmation email was sent to the customer with the reversal reference.'),
    (2, 'Account unlocked after identity verification. The customer has logged in successfully.'),
    (3, 'Root cause: stale OTP gateway session. The gateway was reset and the customer is receiving OTPs again.'),
    (4, 'Late fee waived as a goodwill gesture; the statement has been corrected and re-issued.')
),
lead AS (SELECT id FROM agents WHERE email = 'leela.thomas@untangle.local'),
entries AS (
    -- First touch by the assignee
    SELECT md5('comment:' || s.n || ':touch')::uuid AS id, s.id AS ticket_id, s.assigned_agent_id AS author,
           'COMMENT' AS type, al.body, NULL::jsonb AS metadata,
           coalesce(s.first_response_at, s.created_at + interval '5 minutes') - interval '2 minutes' AS created_at
    FROM seeded s JOIN agent_lines al ON al.i = s.n % 5
    WHERE s.assigned_agent_id IS NOT NULL AND s.created_at < now() - interval '10 minutes'
    UNION ALL
    -- OPEN -> IN_PROGRESS
    SELECT md5('comment:' || s.n || ':start')::uuid, s.id, s.assigned_agent_id, 'SYSTEM',
           'Status changed from OPEN to IN_PROGRESS',
           jsonb_build_object('event', 'STATUS_CHANGED', 'from', 'OPEN', 'to', 'IN_PROGRESS'),
           s.first_response_at
    FROM seeded s WHERE s.status <> 'OPEN'
    UNION ALL
    -- Resolution note + IN_PROGRESS -> RESOLVED
    SELECT md5('comment:' || s.n || ':note')::uuid, s.id, s.assigned_agent_id, 'RESOLUTION_NOTE',
           rl.body, NULL, s.resolved_at - interval '1 minute'
    FROM seeded s JOIN resolution_lines rl ON rl.i = s.n % 5
    WHERE s.status IN ('RESOLVED', 'CLOSED')
    UNION ALL
    SELECT md5('comment:' || s.n || ':resolve')::uuid, s.id, s.assigned_agent_id, 'SYSTEM',
           'Status changed from IN_PROGRESS to RESOLVED',
           jsonb_build_object('event', 'STATUS_CHANGED', 'from', 'IN_PROGRESS', 'to', 'RESOLVED'),
           s.resolved_at
    FROM seeded s WHERE s.status IN ('RESOLVED', 'CLOSED')
    UNION ALL
    -- RESOLVED -> CLOSED by a lead
    SELECT md5('comment:' || s.n || ':close')::uuid, s.id, (SELECT id FROM lead), 'SYSTEM',
           'Status changed from RESOLVED to CLOSED',
           jsonb_build_object('event', 'STATUS_CHANGED', 'from', 'RESOLVED', 'to', 'CLOSED'),
           s.closed_at
    FROM seeded s WHERE s.status = 'CLOSED'
    UNION ALL
    -- Automatic SLA breach escalation (no human author)
    SELECT md5('comment:' || s.n || ':sla')::uuid, s.id, NULL, 'SYSTEM',
           'SLA breached: escalated to level 1',
           jsonb_build_object('event', 'SLA_BREACH', 'level', 1, 'slaDueAt', s.sla_due_at),
           s.sla_due_at
    FROM seeded s WHERE s.escalation_level > 0
)
INSERT INTO ticket_comments (id, ticket_id, author_agent_id, type, body, metadata, created_at)
SELECT id, ticket_id, author, type, body, metadata, created_at FROM entries
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Notifications: SLA breach alerts for the leads (unread), so the bell has content.
-- -----------------------------------------------------------------------------
INSERT INTO notifications (id, recipient_agent_id, type, ticket_id, message, created_at)
SELECT md5('notification:' || t.id || ':' || a.id)::uuid, a.id, 'SLA_BREACH', t.id,
       t.ticket_number || ' breached its ' || t.priority || ' SLA', t.sla_due_at
FROM tickets t
CROSS JOIN agents a
WHERE t.escalation_level > 0
  AND t.id IN (SELECT md5('ticket:' || n)::uuid FROM generate_series(1, 60) AS n)
  AND a.role = 'LEAD' AND a.email LIKE '%@untangle.local'
ON CONFLICT DO NOTHING;
