# PeekAViewMod

Central runtime-state holder and Lua bridge.

**File:** `src/pzmod/peekaview/PeekAViewMod.java`

## Lua exposure

Annotated `@me.zed_0xff.zombie_buddy.Exposer.LuaClass`. ZombieBuddy's class
scanner registers the class with Kahlua under its **simple name**. Lua
reaches it as the global `PeekAViewMod`; package path
(`pzmod.peekaview.PeekAViewMod`) is not resolvable and errors with
`non-table: null` at load.

Class name is `PeekAViewMod` (not `Mod`): unique global, avoids shadowing
the Lua `PeekAView` table used by `PeekAView_Options.lua` for client-side
state.

## Constants

| Name | Value | Meaning |
|------|-------|---------|
| `MIN_RANGE` | 5 | Vanilla raster envelope floor |
| `MAX_RANGE` | 20 | Upper bound; also the size the per-player raster arrays are allocated for in `Patch_IsoCell` |
| `DEFAULT_RANGE` | 20 | |
| `MAX_DRIVING_SPEED_CAP` | 120 | km/h; above every vanilla car's top speed, so max slider = "always on while driving" |
| `DEFAULT_DRIVING_SPEED_THRESHOLD` | 35 | km/h; above Sunday Driver's ~30 km/h soft-cap + small overshoot headroom |

## State fields

All `public static volatile` — render thread reads; Lua UI thread writes
via setters. No ordering needed beyond single-field visibility; patches
tolerate a one-frame lag.

| Field | Type | Default |
|-------|------|---------|
| `enabled` | boolean | `true` |
| `range` | int | `DEFAULT_RANGE` |
| `fixB42Adjacency` | boolean | `true` |
| `maxDrivingSpeedKmh` | int | `DEFAULT_DRIVING_SPEED_THRESHOLD` |

## Setters

- `setEnabled(boolean)`
- `setRange(int)` — clamps to `[MIN_RANGE, MAX_RANGE]`; on change, calls
  `Patch_IsoCell.Patch_GetSquaresAroundPlayerSquare.invalidateCache()`.
- `setFixB42Adjacency(boolean)`
- `setMaxDrivingSpeedKmh(int)` — clamps to `[0, MAX_DRIVING_SPEED_CAP]`.

## Gate: `isActiveForCurrentRenderPlayer()`

Single authoritative gate used at every patch's enter/exit. Returns
`false` when the mod should behave like vanilla for the current render
pass.

Semantics:
1. `!enabled` → `false`.
2. Invalid `IsoCamera.frameState.playerIndex` → `true` (safe default = mod
   active; vanilla fallback lives at the patch level).
3. No `IsoPlayer`, no vehicle → `true`.
4. `maxDrivingSpeedKmh == 0` while in any vehicle → `false` (always off
   in a vehicle).
5. Otherwise `|vehicle speed km/h| < threshold`.

Reads currently-rendering player via `IsoCamera.frameState.playerIndex`,
set by the engine before each per-player render call. In split-screen
each player's pass sees its own value.

## Logging

`trace(String)` / `trace(String, Throwable)` → `stdout`, prefix
`[PeekAView] `.

## Performance

End-to-end cost of the four patches, measured with JFR over 120 s of
driving in-town at ~30 km/h. Baseline = mod off; the last column
includes every filter shipped in `Patch_IsoCell` (frame cache,
wall-adjacency filter, LOS filter) plus the `cutawayVisit` dedup in
`Patch_FBORenderCutaways`.

| | OFF | ON (no opts) | + wall filter | + downstream |
|---|---:|---:|---:|---:|
| Total samples | 5706 | 8348 | 7458 | **6965** |
| Overhead vs OFF | — | +2642 (+46%) | +1752 (+31%) | **+1259 (+22%)** |
| `cutawayVisit` | <38 | 423 | 370 | **1** |
| `IsoCell.getGridSquare` | 216 | 867 | 261 | **179** |

Roughly **52% of the original driving overhead is gone**. `cutawayVisit`
itself is effectively free (1 sample); `getGridSquare` sits *below* the
OFF baseline because the frame cache saves calls vanilla was making
anyway.

Standing still and walking are nearly free — the frame cache absorbs
59/60 calls per second.

Residual overhead at radius 20 now lives mostly in
`CalculateBuildingsToCollapse`'s per-POI
`GetBuildingsInFrontOfCharacter` call, not in our visitor. Not clearly
reducible without heuristic POI dedup that would break the camera-space
projection guarantee.
