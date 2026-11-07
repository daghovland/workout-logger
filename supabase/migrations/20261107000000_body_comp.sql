-- Body-composition tracking: measurements + per-user target plan.
-- One row per user per date (like daily_logs) so offline clients can upsert by date.

create table if not exists body_measurements (
  id             uuid        primary key default gen_random_uuid(),
  user_id        uuid        not null references auth.users(id) on delete cascade,
  date           date        not null,
  weight_kg      numeric(5,2),
  waist_cm       numeric(5,1),
  suprailiac_mm  numeric(4,1),
  abdominal_mm   numeric(4,1),
  chest_mm       numeric(4,1),
  triceps_mm     numeric(4,1),
  note           text,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint body_measurements_user_date_unique unique (user_id, date)
);

-- Target checkpoints. The app interpolates linearly between them.
-- Each user enters their own plan in the app; nothing is seeded here.
create table if not exists body_targets (
  id             uuid        primary key default gen_random_uuid(),
  user_id        uuid        not null references auth.users(id) on delete cascade,
  date           date        not null,
  weight_kg      numeric(5,2),
  waist_cm       numeric(5,1),
  suprailiac_mm  numeric(4,1),
  abdominal_mm   numeric(4,1),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint body_targets_user_date_unique unique (user_id, date)
);

alter table body_measurements enable row level security;
alter table body_targets      enable row level security;

create policy "Users manage own body measurements"
  on body_measurements for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "Users manage own body targets"
  on body_targets for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create index if not exists body_measurements_user_date_idx on body_measurements (user_id, date desc);
create index if not exists body_targets_user_date_idx      on body_targets      (user_id, date);
