-- Add notes column to sessions for AI-assisted session notes
alter table sessions add column if not exists notes text;
