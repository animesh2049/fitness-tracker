# Fitness Tracker: Requirements

## 1. Overview

A native mobile app for planning, running, and tracking a personal strength and mobility routine. The app is fully offline. All data lives on the device and is never synced to a server or another device. The primary use case is a person standing in a gym with their phone, wanting to know what to do next and to log it in as few taps as possible.

## 2. Goals and non-goals

### Goals

- Plan exercises, group them into workout days, and arrange those days into a repeating cycle.
- Show clearly what to do today and guide the user through it with timers.
- Log every set actually performed.
- Use logged history to suggest progressive overload.
- Keep data safe on the device and exportable.

### Non-goals

- Cloud sync, accounts, login, or multi-device support.
- Social features, sharing, leaderboards.
- Nutrition tracking.
- Wearable or heart rate integration.
- Any network access at all. The app must work with airplane mode on.

## 3. Platform and technical constraints

- Native mobile app. Target platform is Android first (the user's phone). iOS support is optional and can be considered later.
- Fully offline. No network permission is required. No analytics, no crash reporting that phones home.
- Storage is a local SQLite database on the device. Rationale: relational data (exercises, groups, cycles, sessions, sets), fast queries over history for charts and progression, robust to crashes, easy to back up as a single file.
- Timers must keep running when the screen is off or the app is in the background. On Android this means a foreground service with a persistent notification during an active session.
- The screen should stay awake while a workout session is active.
- Audio and vibration cues must work when the phone is on silent (vibration at minimum).
- Data survives app updates. Schema migrations must be versioned.

## 4. Domain model

### 4.1 Exercise

A single movement, for example "Bicep curl" or "Plank".

Fields:
- Name (required, unique).
- Type (required): one of
  - `weight`: reps with an external load (dumbbell, barbell, machine).
  - `bodyweight`: reps with body weight, optionally with added load.
  - `timed`: hold or perform for a duration (plank, stretch).
- Muscle group tags (optional, multi-select): chest, back, shoulders, biceps, triceps, legs, glutes, core, mobility, cardio, other.
- Default rest between sets in seconds (optional; falls back to a global default).
- Weight increment for this exercise (for example 2.5 kg for dumbbells, 5 kg for barbell). Used by progression.
- Progression rule (see section 6).
- Notes (optional free text, for example "use the cable machine near the window").

Operations: create, edit, delete, archive. Deleting an exercise that has history should archive it instead, so history is preserved. Archived exercises are hidden from pickers but visible in history.

### 4.2 Set prescription

What is planned for one set of an exercise inside a workout group.

Fields:
- Set number (order).
- For `weight` and `bodyweight`: target reps, target weight (weight is optional for bodyweight).
- For `timed`: target duration in seconds.
- Is warm-up (boolean). Warm-up sets are logged but excluded from progression and personal records.
- Rest after this set in seconds (optional override of the exercise default).

### 4.3 Workout group ("day")

An ordered list of exercises with their set prescriptions, meant to be done in one session. Example: "Push day", "Legs", "Mobility".

Fields:
- Name (required).
- Ordered list of exercise entries. Each entry has an exercise, an ordered list of set prescriptions, and an optional superset link to the next entry.
- Notes (optional).

Operations: create, edit, delete, duplicate. Reorder exercises by drag. Add and remove sets per exercise. "Add set" copies the previous set's targets.

Superset: two or more consecutive entries can be marked as a superset. During a session the app alternates between them set by set and only starts the rest timer after the last exercise of the superset round.

### 4.4 Routine (cycle)

An ordered list of slots that repeats forever. Each slot is either a workout group or a rest day.

Fields:
- Name (required).
- Ordered slots: each is `group(id)` or `rest`.
- Cycle position: which slot is "today". Stored as an integer index plus the date it was last advanced.
- Active flag. Exactly one routine is active at a time. Others can be saved as templates.

Cycle behaviour:
- The cycle advances only when the user completes a session or explicitly skips a slot. It does not advance automatically by calendar date. Rationale: if the user misses Tuesday, Wednesday should still show Tuesday's workout.
- "Skip today" advances the position without logging a session.
- "Rest today" logs a rest day and does not advance the position.
- "Swap" lets the user pick any slot in the cycle to do today. The chosen slot is done today; the cycle then continues from the slot after the originally scheduled one. Optionally the user can choose to also move the pointer past the swapped slot.
- A rest slot advances when the user taps "Done resting" or automatically at the next calendar day.

### 4.5 Session (logged workout)

A record of a workout actually performed.

Fields:
- Date and start time, end time.
- Group used (reference plus a snapshot of the group name in case it is later edited).
- Ordered list of logged exercise entries, each with an ordered list of logged sets.
- Session notes.
- Status: in progress, completed, abandoned.

Logged set fields:
- Actual reps, actual weight, actual duration (whichever apply).
- Target values at the time (snapshot of the prescription).
- Completed (boolean). An incomplete set means the user did not finish the target.
- Is warm-up.
- Rate of perceived exertion (optional, 1 to 10).
- Timestamp completed.

Only one session may be in progress at a time. If the app is killed mid-session, the session must be recoverable on next launch.

### 4.6 Body measurements (optional feature)

Date, body weight, and optional measurements (waist, chest, arms, thighs). Simple log with a chart.

### 4.7 Settings

- Units: kg or lb. Store internally in kg; convert for display.
- Default rest between sets (seconds).
- Default rest between exercises (seconds).
- Countdown before a timed set starts (seconds, default 5).
- Sound on/off, vibration on/off.
- Keep screen awake during session (default on).
- Auto-start rest timer when a set is marked done (default on).

## 5. Functional requirements

### 5.1 Exercise management

- FR1. Create, edit, delete or archive exercises with the fields in 4.1.
- FR2. Search and filter the exercise list by name and muscle group.
- FR3. Ship with a small starter library (around 30 to 40 common exercises) the user can edit or delete.

### 5.2 Group management

- FR4. Create, edit, delete and duplicate groups.
- FR5. Add exercises from the library into a group, reorder them, and define sets per exercise.
- FR6. Mark consecutive exercises as a superset.
- FR7. Show a summary per group: number of exercises, total sets, muscle groups covered, estimated duration.

### 5.3 Routine management

- FR8. Create a routine as an ordered list of group and rest slots.
- FR9. Set one routine as active.
- FR10. Show the cycle visually with today's slot highlighted and the next few slots visible.
- FR11. Allow skip, rest, and swap as described in 4.4.
- FR12. Manually set the cycle position.

### 5.4 Today screen (home)

- FR13. On launch, show today's slot: the group name, its exercises, and for each exercise the planned sets with the progression suggestion already applied (see section 6).
- FR14. One tap to start the session.
- FR15. If a session is in progress, resume it instead.
- FR16. If today is a rest day, say so, show when the next workout is, and offer "Done resting" and "Train anyway".

### 5.5 Active session

- FR17. Show the current exercise, the current set, targets, and the previous session's actuals for the same exercise for reference.
- FR18. Actual values are pre-filled with the targets. The user adjusts with plus and minus buttons or direct entry, then taps "Done" for the set.
- FR19. For `timed` sets, "Start" begins a countdown (settings) followed by the work timer with a cue at the end. The set is marked done automatically, with an option to stop early and log the actual duration.
- FR20. Marking a set done auto-starts the rest timer. The rest screen shows time remaining, the next set's targets, and buttons for skip rest and add 30 seconds.
- FR21. Rest timer continues in the background and notifies (sound and vibration) when finished.
- FR22. The user can add an extra set, remove a set, skip an exercise, or add an unplanned exercise during the session.
- FR23. Per set and per session notes.
- FR24. Finish session shows a summary: duration, total volume, sets completed, any personal records, and the progression changes that will apply next time.
- FR25. Abandon session keeps whatever was logged and marks the session abandoned.

### 5.6 History

- FR26. Calendar view showing which days had sessions, rest days, and skipped days.
- FR27. List view of past sessions with drill-down to every set.
- FR28. Edit or delete a past session (with confirmation).
- FR29. Per exercise history: every logged set across sessions, newest first.

### 5.7 Progress and records

- FR30. Per exercise charts: top set weight over time, total volume per session, estimated one rep max (Epley formula) for weight exercises, best duration for timed exercises.
- FR31. Weekly volume per muscle group.
- FR32. Personal records: heaviest weight for a given rep count, most reps at a given weight, longest duration. Show a small celebration when a record is beaten during a session.

### 5.8 Backup and export

- FR33. Export all data as a single JSON file to a user-chosen location (Android share sheet or file picker).
- FR34. Export session history as CSV.
- FR35. Import a JSON backup, replacing or merging data, with confirmation.
- FR36. Optionally, automatic daily backup to a folder on device storage the user chooses.

### 5.9 Templates

- FR37. Save any group or routine as a template and create new ones from it.

## 6. Progressive overload

The app suggests changes to targets for the next session based on logged history. Suggestions are always shown as a prompt and are never applied silently. The user can accept, edit, or dismiss each suggestion. The accepted values become the new prescription in the group.

### 6.1 Rules

Each exercise has one progression rule. Available rules:

1. **Linear weight**: If every working set in the last session hit its target reps, increase weight by the exercise increment. Applies to `weight` and `bodyweight` with added load.
2. **Double progression**: Define a rep range, for example 8 to 12. Start at the low end. If every working set hit the top of the range, increase weight by the increment and reset reps to the low end. Otherwise, increase the target reps by 1 on sets that hit their target.
3. **Linear time**: If every working set hit its target duration, increase duration by a configured step (default 10 seconds) up to an optional maximum. Applies to `timed`.
4. **Linear reps**: If every working set hit its target reps, add 1 rep (or a configured step). For bodyweight exercises without load.
5. **None**: Never suggest changes.

### 6.2 Deload

If the user fails to hit the targets for the same exercise in N consecutive sessions (default N = 3, configurable), suggest reducing the load by a percentage (default 10 percent) and marking a fresh attempt. For timed exercises reduce the duration by the same percentage.

### 6.3 Details

- Warm-up sets are excluded from the rule evaluation.
- Incomplete or skipped sets count as not hitting the target.
- Rounding: weights round to the exercise increment. Durations round to 5 seconds.
- The suggestion is computed when the today screen loads, from the most recent completed session containing that exercise, not from the calendar date.
- Show a brief reason with each suggestion, for example "Hit 3 x 12 last time at 10 kg. Try 12.5 kg."

## 7. Timers

- Rest timer between sets: duration from set prescription, else exercise default, else global default.
- Rest timer between exercises: global default.
- Work timer for timed sets, with a pre-start countdown.
- All timers: run in the background, survive screen off, are visible in a notification, have cue sounds at end and optionally at 10 seconds remaining, and can be skipped or extended.
- Timer accuracy within 1 second over 10 minutes.

## 8. User interface principles

- One-handed use. Primary actions at the bottom of the screen. Large tap targets, since hands may be sweaty or chalky.
- Minimal taps: starting today's session and logging a set as planned should be one tap each.
- High contrast, readable at arm's length. Dark theme by default with a light option.
- Never lose data on accidental back press. Confirm before leaving an active session.
- Screens: Today, Session, Routine, Groups, Exercises, History, Progress, Settings. Bottom navigation with Today, History, Progress, and a Plan tab that contains Routine, Groups and Exercises.

## 9. Data and reliability

- SQLite as the single source of truth. Write-ahead logging enabled.
- Every set log is written immediately when marked done, not at session end.
- Session recovery on crash or kill.
- Schema migrations are versioned and tested against backups from previous versions.
- Deleting anything with history requires confirmation and, where possible, archives instead.

## 10. Performance

- App cold start to Today screen under 1 second on a mid-range phone.
- History and charts should remain responsive with 5 years of daily sessions (roughly 2000 sessions, 50000 sets).

## 11. Privacy

- No network access, no permissions beyond notifications, vibration, foreground service, and file access for export and import.
- No third-party SDKs that transmit data.

## 12. Out of scope for version 1

The following are desirable but explicitly deferred:

- Body measurements (4.6) and their charts.
- Automatic daily backup (FR36).
- Weekly muscle group volume (FR31).
- Estimated duration per group (part of FR7).
- iOS build.

## 13. Open questions

- Framework choice for the native app (Kotlin with Jetpack Compose, or a cross-platform toolkit such as Flutter or React Native). Affects effort and the iOS option.
- Whether swap should move the cycle pointer past the swapped slot by default.
- Whether to support plate-based weight entry (bar plus plates) or only total weight.

## 14. Diet planner (version 2, designed 2026-09-15)

Design canvas: `design/diet/` (six clickable artboards) and https://claude.ai/artifact/VojP6qYXbnWaqDWEmiQjPJ

### 14.1 Purpose

Show the user what to eat at each of three meals a day, hold the recipe for every meal, and remind them the evening before when a meal needs preparation. Same offline constraints as the rest of the app: local data, local notifications, no network.

### 14.2 Domain model

**Meal** (a recipe). Name (required, unique). Slot tags (multi-select: breakfast, lunch, dinner). Servings the recipe describes (default 1). Cook time in minutes (optional). Per-serving calories, protein, carbs, fat (all optional). Ordered ingredients, each with a name, an amount per serving, and a unit (free text: g, ml, cup, katori, tsp, piece). Ordered procedure steps (free text). Needs prep the day before (boolean) plus a prep instruction (text). Notes. Archived flag. Deleting a meal used in the week plan empties those slots after confirmation.

**Week plan.** Seven days by three slots (breakfast, lunch, dinner). Each cell holds a meal and a serving multiplier (default 1) or is empty. The plan repeats every calendar week. One plan is active; others can be saved as templates. "Copy a day to all days" fills the week from one day.

**Meal windows** (settings). Start and end time for each slot; defaults breakfast 06:00 to 10:30, lunch 11:30 to 15:30, dinner 18:30 to 22:00. Windows must not overlap.

**Reminder settings.** Prep reminder on/off and time (default 21:00). Meal window reminder on/off.

### 14.3 Functional requirements

- FR38. Create, edit, delete and archive meals with the fields in 14.2. Ingredient and step lists support add, remove and reorder. Deleting a meal that the week plan uses warns how many slots become empty and asks for confirmation.
- FR39. Meal detail shows slot tags, cook time, macros, ingredients, the day-before instruction if any, and the numbered procedure. A servings stepper scales every ingredient amount and the macros; the stored recipe is not changed.
- FR40. Week plan screen: a 7 by 3 grid. Tapping a cell opens a picker listing only meals tagged for that slot, plus "leave empty". Cells of prep-ahead meals carry a marker. Each day shows its protein total (and calories where every meal has macros).
- FR41. Diet tab (new fifth bottom tab, placed after Today): shows today's plan. The meal whose window contains the current time is the current meal, shown large with quantity, macros and a button to its recipe. Meals before it are marked done, later ones later. Between windows the tab shows the next meal and its start time; after the last window it shows tomorrow's breakfast. Tapping any meal row expands its ingredient quantities.
- FR42. Prep reminder: on each day, at the prep reminder time, if any meal planned for the next calendar day needs prep the day before, post one local notification listing each such meal and its prep instruction, with actions "Done" and "Open recipe". No notification when nothing needs prep. The same information appears as a banner on the Diet tab from the prep reminder time until midnight.
- FR43. Meal window reminder (optional): when a window opens, post a local notification naming the planned meal, its quantity and macros. Tapping it opens the Diet tab.
- FR44. Meals, the week plan and the diet settings are included in JSON export and import (schema version bump with migration).

### 14.4 Non-goals for this version

- Logging what was actually eaten, calorie tracking over time, and diet history.
- Grocery lists (a natural follow-up: sum ingredient amounts across the week).
- Nutrition lookup from a food database; macros are typed by the user.
