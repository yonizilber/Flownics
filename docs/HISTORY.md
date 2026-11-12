# How Flownics was built

Flownics was planned first and then built in phases. Each phase ended with an app that worked on
the phone before the next one started. Version control begins with the commit that adds this
file. Everything before it is described here, from the project's planning notes. It is not
reconstructed as commits, because those intermediate states were never committed and inventing
them would misrepresent the history.

## The plan (early November 2026)

These were decided before any code was written:

- **AI keys:** every user brings their own key, encrypted on the device with the Android
  Keystore. There is no server and there are no accounts. Everything stays on the phone, and
  sharing and backup go through files.
- **Architecture:** `data/` (Room, network, crypto, DI), `domain/` (pure Kotlin, unit-tested)
  and `feature/<screen>/` (Compose screen + ViewModel).
- **Rules held throughout:**
  - AI output is always a proposal the user approves.
  - Failures are shown, never silently patched over.
  - User content and web results are treated as data, never as instructions (prompt
    injection).
  - Permissions are kept to the minimum.
  - Hebrew and English, with full right-to-left support.

## Phases

| Phase | What it added |
|---|---|
| 0 | Project setup: Kotlin, Compose, Material 3, Hilt, Room, version catalog, the Keystore cipher, Hebrew and English strings, navigation. |
| 1 | Exercise library: categories, muscles, cues, stages, photos and videos via the Photo Picker, search and filters. |
| 2 | Workout builder: blocks, supersets and circuits, rest, **active rest** (a stretch or a pool of mobility exercises in the rests). The `WorkoutCompiler` flattens a workout into steps and is fully unit-tested. |
| 3 | Player: a state machine over the steps, timers worked out from monotonic timestamps (right after screen-off, rotation and process death), a foreground service, Hebrew TTS, sounds and vibration, set logging. |
| 4 | History and progress: logs, charts, personal records, weekly muscle load, "add to calendar" (no permission needed). |
| 5 | AI: Gemini and Claude with the user's own keys, a monthly spending cap, an activity log, structured answers checked against the app's own lists, sources taken only from real search results. Features: exercise details, variations, workout review, workout drafts, a body map. |
| 6 | Sharing and backup: a versioned zip format with strict reading (no zip-slip, size limits, type checks by magic bytes), merge-import from friends, full restore, automatic backup to a chosen folder. |

## After the phases

| Date | Round | What changed |
|---|---|---|
| 2026-11-07 | AI hardening | Hard deadlines with a running clock and cancel, a log of every call (including failures), demonstration videos checked against YouTube oEmbed, exercise levels and families of variations. |
| 2026-11-08 | Gemini and body map | Lighter thinking for list answers; a self-drawn body map (no licensing issue). |
| 2026-11-10 | Streaming | Both providers stream. The deadline became a stall watchdog. The failure reason is logged. |
| 2026-11-10 | **The coach** | From one-off AI questions to an agent: a provider-neutral tool loop (Gemini thought signatures passed back raw; Claude strict client tools), read tools over the user's data, and changes only as proposal cards. Its memory is visible and can be undone. |
| 2026-11-11 | Feedback round | Several kinds per exercise; families of styles (one default with its styles); manual activities with interval parts; the coach can propose exercise edits and log activities. |
| 2026-11-12 | Plan and calendar | Planned workouts and runs with pace and heart-rate targets; a journal (week view); planned-vs-actual by part; opt-in sync to a Google calendar that touches only its own events; planning tools for the coach. |
| 2026-11-12 | Garmin | Its own FIT decoder (tested on real recordings from several devices); import of `.fit` files; optional sync with Garmin's personal API key; laps become parts; cadence, step length, heart-rate drift and per-km splits. |
| 2026-11-12 | Watch + app | A watch recording made during an app workout joins that workout: heart rate set by set, with a chart. A review fixed a calendar-sync loop. |

The database schema went from v1 to v11 by the first commit, with a real migration for every version
(Room's exported schemas are in `app/schemas`).

From here on, history is in the git log (from 2026-11-12: combining workouts, background
Garmin sync, the month view, reminders, heart-rate zones and load, live heart rate over
Bluetooth).
