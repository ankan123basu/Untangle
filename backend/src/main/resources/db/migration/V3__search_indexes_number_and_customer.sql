-- Search (`q`) matches ticket_number, subject, customer_name and description with ILIKE '%term%'.
-- V1 indexed only subject and description, so the OR forced a full table scan on every search:
-- the stress test at 200k tickets found it was the top database cost (about 100 ms, 3 cores).
-- With all four columns indexed, Postgres combines them with a BitmapOr (about 8 ms).
CREATE INDEX ix_tickets_number_trgm   ON tickets USING gin (ticket_number gin_trgm_ops);
CREATE INDEX ix_tickets_customer_trgm ON tickets USING gin (customer_name gin_trgm_ops);
