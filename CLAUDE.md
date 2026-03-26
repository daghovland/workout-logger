# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development

No build process — this is a vanilla JS/HTML/CSS PWA. To develop locally:

```bash
python3 -m http.server 8000
# Open http://localhost:8000/docs/
```

The app lives entirely in `docs/index.html`. There are no dependencies, no npm, no compilation step.

## Architecture

Single-file PWA (`docs/index.html`, ~1043 lines) with:

- **IndexedDB** (`daglifts` db, `sessions` store) for persistent workout history
- **Service worker** (`docs/sw.js`) for offline support — cache-first for assets, passes Anthropic API calls through
- **Claude API** (`claude-sonnet-4-20250514`) for AI coaching, called directly from the browser with a user-supplied API key

### App state (in-memory)
- `currentMode` — `'gym'` or `'street'`
- `currentSession` — `{exId: [{weight, reps, done}]}`
- `aiHistory` — conversation turns sent to Claude

### Exercise modes
- **Gym**: rehab block (5 exercises) + strength block (5 exercises)
- **Street Barbell**: 6 outdoor exercises

Exercises and default working weights are defined in `GYM_EXERCISES`, `STREET_EXERCISES`, and `DEFAULTS` at the top of the JS section.

### Session flow
1. User selects mode → exercise cards render with last session's values pre-filled
2. User logs sets (weight × reps × done checkbox)
3. "Save session" writes to IndexedDB and resets UI
4. History panel reads last 10 sessions from IndexedDB

### Claude integration
- Panel auto-greets with a session assessment on open
- System prompt is personalized for Dag: patellar tendon rupture rehab history, Norwegian lifter context
- Session data is injected into the greeting prompt for context

## Deployment

GitHub Pages serves from `/docs`. No CI needed — push to `main` and it's live.

When bumping the service worker cache (e.g. after changing `index.html`), update the cache name in `docs/sw.js` (`daglifts-v1` → `daglifts-v2`, etc.) to force clients to pick up the new version.
