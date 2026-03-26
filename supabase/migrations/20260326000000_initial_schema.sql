-- Sessions table — mirrors the IndexedDB session structure
create table if not exists sessions (
  id           uuid        primary key default gen_random_uuid(),
  user_id      uuid        not null references auth.users(id) on delete cascade,
  local_id     bigint      not null,          -- the timestamp-based local id from IndexedDB
  type         text        not null check (type in ('gym', 'outdoor')),
  date         timestamptz not null,
  duration_ms  int,
  created_at   timestamptz default now(),
  constraint sessions_user_local_unique unique (user_id, local_id)
);

-- Sets table — one row per logged set
create table if not exists sets (
  id            uuid        primary key default gen_random_uuid(),
  session_id    uuid        not null references sessions(id) on delete cascade,
  exercise_id   text        not null,
  exercise_name text        not null,
  set_index     int         not null,
  weight        float,                        -- symmetric kg
  weight_l      float,                        -- asymmetric left kg
  weight_r      float,                        -- asymmetric right kg
  reps          int,
  notes         text,
  logged_at     timestamptz
);

-- Row level security
alter table sessions enable row level security;
alter table sets     enable row level security;

create policy "Users manage own sessions"
  on sessions for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "Users select own sets"
  on sets for select
  using (session_id in (select id from sessions where user_id = auth.uid()));

create policy "Users insert own sets"
  on sets for insert
  with check (session_id in (select id from sessions where user_id = auth.uid()));

create policy "Users delete own sets"
  on sets for delete
  using (session_id in (select id from sessions where user_id = auth.uid()));

-- Indexes
create index if not exists sessions_user_id_idx       on sessions (user_id);
create index if not exists sets_session_id_idx        on sets (session_id);
create index if not exists sets_exercise_logged_at_idx on sets (exercise_id, logged_at desc);
