-- Stress-test data for the separate `untangle-stress` stack ONLY (make stress). Never run it on
-- your real database. Adds 41 load agents (50 in total), 200,000 tickets over the last year
-- (10% OPEN, 10% IN_PROGRESS, 20% RESOLVED, 60% CLOSED) and 3 timeline entries per ticket.
-- 41 extra agents (50 in total), same demo password hash as the seed
INSERT INTO agents (full_name, email, password_hash, role, created_at, updated_at)
SELECT 'Load Agent ' || g, 'load.agent' || g || '@untangle.local',
       (SELECT password_hash FROM agents WHERE email = 'riya.sen@untangle.local'), 'AGENT', now(), now()
FROM generate_series(1, 41) g;

CREATE TEMP TABLE ag AS SELECT row_number() OVER () - 1 AS i, id FROM agents WHERE role IN ('AGENT','LEAD');
CREATE TEMP TABLE lead AS SELECT id FROM agents WHERE email = 'leela.thomas@untangle.local';

-- 200,000 tickets over the last year: 10% OPEN, 10% IN_PROGRESS, 20% RESOLVED, 60% CLOSED
INSERT INTO tickets (subject, description, customer_name, customer_email, category, priority, status,
                     assigned_agent_id, created_by_agent_id, source, escalation_level, sla_started_at,
                     sla_due_at, first_response_at, resolved_at, closed_at, created_at, updated_at)
SELECT
  (ARRAY['Refund not received','Card payment failed','App crashes on login','Account locked',
         'Double charge on card','Wallet top-up failed','Cannot reset password','Order not delivered'])[1 + g % 8]
     || ' #' || g,
  'Customer reports an issue that needs attention. Reference ' || md5(g::text),
  'Customer ' || g, 'customer' || g || '@example.com',
  (ARRAY['BILLING','TECHNICAL','ACCOUNT','REFUND','GENERAL','OTHER'])[1 + g % 6],
  (ARRAY['LOW','MEDIUM','MEDIUM','HIGH','HIGH','CRITICAL'])[1 + (g * 7) % 6],
  s.status,
  CASE WHEN s.status = 'OPEN' AND g % 3 = 0 THEN NULL
       ELSE (SELECT id FROM ag WHERE ag.i = g % (SELECT count(*) FROM ag)) END,
  (SELECT id FROM lead), 'MANUAL',
  CASE WHEN g % 9 = 0 THEN 1 ELSE 0 END,
  c.created, c.created + interval '4 hours',
  CASE WHEN s.status <> 'OPEN' THEN c.created + interval '20 minutes' END,
  CASE WHEN s.status IN ('RESOLVED','CLOSED') THEN c.created + interval '3 hours' END,
  CASE WHEN s.status = 'CLOSED' THEN c.created + interval '1 day' END,
  c.created, c.created
FROM generate_series(1, 200000) g
CROSS JOIN LATERAL (SELECT CASE WHEN g % 10 = 0 THEN 'OPEN' WHEN g % 10 = 1 THEN 'IN_PROGRESS'
                                WHEN g % 10 IN (2,3) THEN 'RESOLVED' ELSE 'CLOSED' END AS status) s
CROSS JOIN LATERAL (SELECT now() - (g % 365) * interval '1 day' - (g % 1440) * interval '1 minute' AS created) c;

-- 3 timeline entries per ticket: a created event, a comment, and a note or status change
INSERT INTO ticket_comments (ticket_id, author_agent_id, type, body, metadata, created_at)
SELECT t.id, NULL, 'SYSTEM', 'Ticket created', '{"event":"CREATED"}'::jsonb, t.created_at FROM tickets t;
INSERT INTO ticket_comments (ticket_id, author_agent_id, type, body, created_at)
SELECT t.id, coalesce(t.created_by_agent_id, t.assigned_agent_id, (SELECT id FROM lead)), 'COMMENT', 'Called the customer, investigating.', t.created_at + interval '30 minutes' FROM tickets t;
INSERT INTO ticket_comments (ticket_id, author_agent_id, type, body, created_at)
SELECT t.id, coalesce(t.assigned_agent_id, t.created_by_agent_id, (SELECT id FROM lead)),
       CASE WHEN t.resolved_at IS NOT NULL THEN 'RESOLUTION_NOTE' ELSE 'COMMENT' END,
       'Update from the assignee.', t.created_at + interval '2 hours' FROM tickets t;
ANALYZE;
SELECT (SELECT count(*) FROM tickets) tickets, (SELECT count(*) FROM ticket_comments) timeline,
       (SELECT count(*) FROM agents) agents, pg_size_pretty(pg_database_size(current_database())) db_size;
