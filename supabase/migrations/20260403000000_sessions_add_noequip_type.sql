-- Allow 'noequip' session type (home/no-equipment sessions added after initial schema)
alter table sessions
  drop constraint if exists sessions_type_check,
  add constraint sessions_type_check check (type in ('gym', 'outdoor', 'noequip'));
