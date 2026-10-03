# Android App — Implementation Plan

## Current state summary

The Android app already has:
- **Supabase sync**: sessions pushed/pulled on sign-in, daily logs upserted, chat history persisted
- **LLM via edge functions**: `session-coach` (pre-filled weights + coach brief), `chat`, `home-brief` — all wired through `SupabaseRepository`
- **Auth plumbing**: Google OAuth via Supabase SDK, deep-link manifest entry, `onNewIntent` handler

None of the LLM or sync features work in practice because **auth is broken**: sign-in completes in the browser but the app doesn't detect the session on cold-start. Everything below auth is blocked.

---

## Phase 1 — Fix auth (critical, blocks everything) ✅ in progress

### 1a. Call `handleDeeplinks` in `onCreate` (not just `onNewIntent`)
`onCreate` is called when the app is launched *fresh* from the OAuth redirect URL (the common case — the user tapped "Sign in", the browser opened, they signed in, Android brought the app back). Currently `onNewIntent` handles it (only fires if the app was already running), so cold-start auth silently fails.

**File**: `MainActivity.kt` — add `supabaseClient.handleDeeplinks(intent)` in `onCreate`.

### 1b. Subscribe to Supabase auth state changes
`initAuth()` checks the current user once at startup. It doesn't react to session changes (OAuth callback, token refresh, sign-out from another device). The Supabase SDK exposes `auth.authStateFlow` — collect it in the ViewModel init block so `isSignedIn` stays accurate.

**File**: `WorkoutViewModel.kt` — replace one-shot `initAuth()` with a `authStateFlow` collector.

---

## Phase 2 — Verify LLM + sync end-to-end

Once auth is working, these should work without code changes — but verify each:

| Feature | Code path | Expected behaviour |
|---|---|---|
| Session-coach suggestions | `loadCoachForSession` → `supabaseRepo.fetchCoach` | Weights/reps pre-filled when session starts |
| Home brief | `refreshHomeBrief` → `supabaseRepo.fetchHomeBrief` | AI recommendation card on home screen |
| Chat | `sendChatMessage` → `supabaseRepo.sendChat` | Replies appear, persisted to Supabase |
| Cross-device sync | `syncPending` on sign-in | Sessions from PWA appear in app history |

---

## Phase 3 — Missing features (priority order)

### 3a. Auth UX: navigate to home after sign-in
After OAuth completes, the user is still on ProfileScreen. Should pop back to home once `isSignedIn` turns true.

**File**: `ProfileScreen.kt` — observe `homeState.isSignedIn` and call `onBack()` when it flips to true.

### 3b. Profile: coaching context fields
The PWA stores `training_background`, `goals`, and `injuries` in `user_profiles`. The AI uses this as its system-prompt context. Without it, the Android app's AI responses are generic.

**Files**: 
- `SupabaseRepository.kt` — add `fetchProfile()` and `saveProfile()` 
- `ProfileScreen.kt` — add editable text fields for the three context strings
- `WorkoutViewModel.kt` — load profile on sign-in, save on change

### 3c. Chat: session context injection
The PWA sends current session state (elapsed time, exercises logged, sets) alongside chat messages when mid-workout. The Android `sendChatMessage` sends no session context. The edge function supports `session_data` as a string parameter.

**File**: `WorkoutViewModel.kt` — serialize current `SessionUiState.Active` into `session_data` when sending chat.

### 3d. Chat: handle `updated_coach_notes`
`ChatResponse` already has `updatedCoachNotes` field (from `"updated_coach_notes"` in edge function response). The ViewModel ignores it. Should upsert to `user_profiles.coach_notes` and show a toast.

**File**: `WorkoutViewModel.kt` — handle `response.updatedCoachNotes != null`.

### 3e. Chat: quick-action buttons
The PWA has "Brief me", "Progress", "Recovery", "Session focus" chips above the input. Speeds up common interactions.

**File**: `ChatScreen.kt` — add a horizontal chip row above the text input.

### 3f. Daily log UI
`saveDailyLog()` exists in the ViewModel and syncs to Supabase. There is no UI entry point. Add a collapsible card on `HomeScreen` below the decline squat tracker: sleep hours (number field) + activity notes (text field) + save button for today's log.

**Files**: `HomeScreen.kt` — add `DailyLogCard` composable; hook into `vm.saveDailyLog`.

### 3g. Coach notes display on ProfileScreen
`user_profiles.coach_notes` is the AI's working memory. Show it read-only (or editable) on ProfileScreen so the user can see/clear what the AI has saved about them.

---

## Phase 4 — Polish (lower priority)

| Item | Notes |
|---|---|
| Crash-safe session draft | Write active session to DataStore on each set; restore on cold-start |
| History: show DSQ + daily logs | Merge `dailyLogs` into `sessions` list, sorted by date |
| Session edit | Allow editing date/notes/sets on a saved session |
| Export / import JSON | Match PWA's export format for interoperability |

---

## Implementation order

1. ✅ Phase 1a — `handleDeeplinks` in `onCreate` 
2. ✅ Phase 1b — reactive auth state via `sessionStatus` flow
3. ✅ Phase 3a — navigate home after sign-in (ProfileScreen LaunchedEffect)
4. ✅ Phase 3b — profile coaching context (training_background, goals, injuries + coach_notes display)
5. ✅ Phase 3c — chat session context injection (elapsed time + sets logged)
6. ✅ Phase 3d — coach notes auto-update from chat response
7. ✅ Phase 3e — chat quick-action chips (Brief me, Progress, Recovery, Session focus)
8. Phase 3f — daily log UI
9. Phase 4 items as time allows
