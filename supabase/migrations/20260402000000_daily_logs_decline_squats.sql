-- Add decline_squats counter to daily_logs for cross-device DSQ tracking
alter table daily_logs
  add column if not exists decline_squats integer;
