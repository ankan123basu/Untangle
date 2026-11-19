-- =============================================================================
-- V5: the AI teammate gets its own name, "Unty" (from "untie" + Untangle).
--
-- Simple explanation: the AI teammate was called "Untangle AI"; it is now "Unty", with a matching
-- address. Only the default row is renamed: if an admin already gave it another name, it stays.
-- Technical details: messages and the UI read the name from this row, so nothing else changes.
-- =============================================================================

UPDATE agents
SET full_name = 'Unty',
    email = 'unty@untangle.local',
    updated_at = now()
WHERE kind = 'AI'
  AND full_name = 'Untangle AI'
  AND NOT EXISTS (SELECT 1 FROM agents WHERE lower(email) = 'unty@untangle.local');
