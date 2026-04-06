-- Unified coach chat history, synced cross-device.
-- All AI interactions (home brief, session notes, general coaching) land here.
create table if not exists chat_messages (
  id          uuid        primary key default gen_random_uuid(),
  user_id     uuid        references auth.users not null,
  role        text        not null check (role in ('user', 'assistant')),
  content     text        not null,
  context_type text       check (context_type in ('home', 'session', 'general')),
  created_at  timestamptz default now()
);

alter table chat_messages enable row level security;

create policy "users manage own chat messages" on chat_messages
  for all using (auth.uid() = user_id);

-- Coach working-memory: goals, concerns, strategies. Updated by AI during chat.
alter table user_profiles
  add column if not exists coach_notes text;
