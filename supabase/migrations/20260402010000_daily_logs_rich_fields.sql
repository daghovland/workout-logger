-- Add structured notes and activity JSON to daily_logs for cross-device rich log sync.
-- Rich daily logs (imported from JSON export) store coaching-relevant context here
-- so they are available on all devices, not just the browser that imported them.
alter table daily_logs
  add column if not exists notes        text,
  add column if not exists rich_activity jsonb;
