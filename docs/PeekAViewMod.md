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
| `MAX_RANGE` | 20 | Wall-cutaway upper bound; raster arrays in `Patch_IsoCell` are sized for this |
| `DEFAULT_RANGE` | 15 | Wall-cutaway default |
| `MIN_TREE_FADE_RANGE` | 5 | Tree-fade lower bound |
| `MAX_TREE_FADE_RANGE` | 25 | Tree-fade upper bound (independent of cutaway) |
| `DEFAULT_TREE_FADE_RANGE` | 20 | Tree-fade default |
| `MAX_DRIVING_SPEED_CAP` | 120 | km/h; above every vanilla car's top speed |
| `DEFAULT_DRIVING_SPEED_THRESHOLD` | 35 | km/h; covers Sunday-Driver cap with headroom |
| `DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD` | 50 | km/h; above this the per-tree fade-up animation can't keep up with scenery scrolling onto screen |

## State fields

All `public static volatile` — render thread reads; Lua UI thread writes
via setters. No ordering needed beyond single-field visibility; patches
tolerate a one-frame lag.

| Field | Type | Default |
|-------|------|---------|
| `enabled` | boolean | `true` |
| `aimStanceOnly` | boolean | `false` |
| `range` | int | `DEFAULT_RANGE` |
| `maxDrivingSpeedKmh` | int | `DEFAULT_DRIVING_SPEED_THRESHOLD` |
| `fixB42Adjacency` | boolean | `true` |
| `fadeNWTrees` | boolean | `true` |
| `treeFadeRange` | int | `DEFAULT_TREE_FADE_RANGE` |
| `treeFadeMaxDrivingSpeedKmh` | int | `DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD` |

## Setters

- `setEnabled(boolean)`
- `setAimStanceOnly(boolean)`
- `setRange(int)` — clamps to `[MIN_RANGE, MAX_RANGE]`; on change calls
  `Patch_IsoCell.Patch_GetSquaresAroundPlayerSquare.invalidateCache()`.
- `setMaxDrivingSpeedKmh(int)` — clamps to `[0, MAX_DRIVING_SPEED_CAP]`.
- `setFixB42Adjacency(boolean)`
- `setFadeNWTrees(boolean)` — invalidates
  `Patch_calculateObjectsObscuringPlayer`'s Location cache so a
  toggle-off across a range change can't leak a stale list back when
  toggled on.
- `setTreeFadeRange(int)` — clamps to
  `[MIN_TREE_FADE_RANGE, MAX_TREE_FADE_RANGE]`; on change invalidates
  the same Location cache.
- `setTreeFadeMaxDrivingSpeedKmh(int)` — clamps to
  `[0, MAX_DRIVING_SPEED_CAP]`.

## Gates: cutaway vs. tree-fade

Two authoritative gates, one per feature group, used at every patch's
enter/exit. Both share the same per-frame memo to avoid duplicating
the player/vehicle lookup.

```java
public static boolean isActiveCutawayForCurrentRenderPlayer();
public static boolean isActiveTreeFadeForCurrentRenderPlayer();
```

Cutaway-side patches (`Patch_GetSquaresAroundPlayerSquare`,
`Patch_cutawayVisit`, `Patch_shouldCutaway`,
`Patch_isAdjacentToOrphanStructure`) call the cutaway gate.
Tree-fade patches (`Patch_isTranslucentTree`,
`Patch_calculateObjectsObscuringPlayer`, `Patch_DrawStencilMask`)
call the tree-fade gate. Each gate honors its own
`maxDrivingSpeedKmh` threshold so the user can keep tree-fade
on while driving fast even after wall-cutaway has gated off.

Semantics (computed in `refreshActiveCache`):

1. `!enabled` → both `false`.
2. Invalid `IsoCamera.frameState.playerIndex` → both `true` (safe
   default = mod active; vanilla fallback at the patch level).
3. `aimStanceOnly && !player.isAiming()` → both `false`.
4. No `IsoPlayer`, no vehicle → both `true`.
5. In a vehicle: each gate is `threshold > 0 && |speed| < threshold`.
   Threshold 0 means "always off in a vehicle" for that feature.

Reads currently-rendering player via `IsoCamera.frameState.playerIndex`,
set by the engine before each per-player render call. In split-screen
each player's pass sees its own value.

### Per-frame memo

Both gates share one cache key `(frameCount, playerIndex)` and two
result slots. `refreshActiveCache` writes both result slots in one
pass — never two computeIsActive calls for the same frame.

```java
public static volatile int activeCacheFrameCount = Integer.MIN_VALUE;
public static volatile int activeCachePlayerIndex = Integer.MIN_VALUE;
public static volatile boolean activeCacheCutaway = false;
public static volatile boolean activeCacheTreeFade = false;
```

Side effect: every patch in one render pass for one player sees the
**same** snapshot. Earlier code could re-evaluate `isAiming` or vehicle
speed across patches within one frame and theoretically flicker at
threshold transitions. Now consistent within a frame, with at most one
frame of lag at threshold crossings (visually imperceptible).

Single-slot cache thrashes in split-screen (one slot, two alternating
playerIndices), but a recompute miss is ~10 ops — not worth a
per-player array.

## Logging

`trace(String)` / `trace(String, Throwable)` → `stdout`, prefix
`[PeekAView] `.

## Performance

JFR-driven optimization (2026-04-26) measured tree-fade overhead on a
120 s straight-line driving run (Rosewood→Muldraugh, range=20, max
driving threshold), comparing baseline (`fadeNWTrees=off`) vs. the
fully-optimized stack with the per-frame `isActive` cache, the Location
frame-cache, the `addAll`/`toArray` bypass, and the access-context
`rebuildCache` fix.

| Metric | OFF | ON (cached) |
|---|---:|---:|
| Top-Allocator | `Class[]` 76% (JIT background) | `ArrayList$SubList` 58% (vanilla UI Lua, not us) |
| `Location` allocator | not in top-17 | not in top-17 ✓ |
| Total GC pause | 64.3 ms | 90.5 ms |
| Median GC pause | 12.6 ms | 11.3 ms ≈ baseline |
| `IllegalAccessError` count | 0 | 0 |

CPU hot-method deltas all within ±10% of baseline noise. Tree-fade adds
~26 ms total GC-pressure across 120 s — one extra short young-gen GC,
not a steady stutter source. Median per-pause is identical to baseline.

Standing still and walking remain nearly free — the cutaway frame
cache absorbs 59/60 calls per second; the Location frame-cache
absorbs every Location allocation while the player tile is unchanged.
