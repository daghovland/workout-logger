-- Daily log for sleep and non-gym activity — feeds into session brief context
create table if not exists daily_logs (
  id          uuid    primary key default gen_random_uuid(),
  user_id     uuid    not null references auth.users(id) on delete cascade,
  date        date    not null,
  sleep_hours float,
  activity    text,
  updated_at  timestamptz default now(),
  unique (user_id, date)
);

alter table daily_logs enable row level security;

create policy "Users manage own daily logs"
  on daily_logs for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create index if not exists daily_logs_user_date_idx on daily_logs (user_id, date desc);
