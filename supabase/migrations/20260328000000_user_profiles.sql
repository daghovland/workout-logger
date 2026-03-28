-- User profiles table — stores personal coaching context and exercise defaults
create table if not exists user_profiles (
  user_id           uuid        primary key references auth.users(id) on delete cascade,
  display_name      text,
  ai_context        text,        -- free-text coaching bio sent to the AI (injury history, goals, etc.)
  exercise_defaults jsonb        default '{}'::jsonb,  -- {exercise_id: {weight, reps, weightL, weightR}}
  updated_at        timestamptz  default now()
);

alter table user_profiles enable row level security;

create policy "Users manage own profile"
  on user_profiles for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);
