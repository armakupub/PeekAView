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
| `MIN_RANGE` | 5 | Wall-cutaway lower bound. At this slider value `Patch_GetSquaresAroundPlayerSquare` falls through to vanilla. |
| `MAX_RANGE` | 20 | Wall-cutaway upper bound; raster arrays in `Patch_IsoCell` are sized for this. |
| `DEFAULT_RANGE` | 15 | Wall-cutaway slider default. |
| `MIN_TREE_FADE_RANGE` | 5 | Tree-fade lower bound. |
| `MAX_TREE_FADE_RANGE` | 25 | Tree-fade upper bound (independent of cutaway). |
| `DEFAULT_TREE_FADE_RANGE` | 20 | Tree-fade slider default. |
| `MAX_DRIVING_SPEED_CAP` | 120 | km/h; above every vanilla car's top speed. |
| `DEFAULT_DRIVING_SPEED_THRESHOLD` | 35 | km/h; cutaway driving-speed default. |
| `DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD` | 100 | km/h; tree-fade driving-speed default. Effectively "always on while driving" for normal car speeds. |
| `TREE_FADE_SNAP_SPEED_CAP_KMH` | 30f | km/h cap for the speed-proportional fade boost in `Patch_isTranslucentTree`. At or above this speed translucent trees lerp to `minAlpha` in one frame; below, the lerp factor is `speed / cap`. |

## State fields

All `public static volatile` — render thread reads; Lua UI thread writes
via setters. No ordering needed beyond single-field visibility; patches
tolerate a one-frame lag.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | boolean | `true` | Master switch. |
| `aimStanceOnly` | boolean | `false` | Restricts cutaway and tree-fade to nimble-stance/aim. |
| `range` | int | `DEFAULT_RANGE` | Wall-cutaway slider value. |
| `maxDrivingSpeedKmh` | int | `DEFAULT_DRIVING_SPEED_THRESHOLD` | Cutaway off above this speed in vehicles. `0` = always off in vehicles. |
| `fixB42Adjacency` | boolean | `true` | B42 wall-hiding bug workaround toggle. Independent of `aimStanceOnly`. |
| `fadeNWTrees` | boolean | `true` | Tree-fade master toggle. |
| `treeFadeRange` | int | `DEFAULT_TREE_FADE_RANGE` | Tree-fade slider value. |
| `treeFadeMaxDrivingSpeedKmh` | int | `DEFAULT_TREE_FADE_DRIVING_SPEED_THRESHOLD` | Tree-fade off above this speed in vehicles. `0` = always off in vehicles. |
| `treeFadeStayOnWhileDriving` | boolean | `false` | Override: when on, the `aimStanceOnly` gate does not block tree-fade while in a vehicle. |
| `treeFadeStayOnWhileOnFoot` | boolean | `true` | Override: when on, the `aimStanceOnly` gate does not block tree-fade while on foot. Default on — the typical user enabling nimble-stance does so for around-the-corner wall-cutaway peeks and still wants tree-fade everywhere. |
| `currentVehicleSpeedKmh` | float | `0f` | Per-frame cache, written in `refreshActiveCache`. Read by `Patch_isTranslucentTree.exit` for the speed-proportional fade boost. `0f` when not in a vehicle. |

## Setters

- `setEnabled(boolean)`
- `setAimStanceOnly(boolean)`
- `setRange(int)` — clamps to `[MIN_RANGE, MAX_RANGE]`; on change calls
  `Patch_IsoCell.Patch_GetSquaresAroundPlayerSquare.invalidateCache()`.
- `setMaxDrivingSpeedKmh(int)` — clamps to `[0, MAX_DRIVING_SPEED_CAP]`.
- `setFixB42Adjacency(boolean)`
- `setFadeNWTrees(boolean)`
- `setTreeFadeRange(int)` — clamps to
  `[MIN_TREE_FADE_RANGE, MAX_TREE_FADE_RANGE]`.
- `setTreeFadeMaxDrivingSpeedKmh(int)` — clamps to
  `[0, MAX_DRIVING_SPEED_CAP]`.
- `setTreeFadeStayOnWhileDriving(boolean)`
- `setTreeFadeStayOnWhileOnFoot(boolean)`

## Gates: cutaway vs. tree-fade

Two authoritative gates, one per feature group, used at every patch's
enter/exit. Both share the same per-frame memo to avoid duplicating
the player/vehicle lookup.

```java
public static boolean isActiveCutawayForCurrentRenderPlayer();
public static boolean isActiveTreeFadeForCurrentRenderPlayer();
```

Cutaway-side patches (`Patch_GetSquaresAroundPlayerSquare`,
`Patch_cutawayVisit`) call the cutaway gate. Tree-fade patches
(`Patch_isTranslucentTree`, `Patch_DrawStencilMask`) call the
tree-fade gate. The two B42-fix
patches (`Patch_shouldCutaway`, `Patch_isAdjacentToOrphanStructure`)
do **not** call either gate — they check `enabled` and
`fixB42Adjacency` only and run regardless of stance, vehicle, or
speed, because the underlying vanilla bug exists at vanilla cutaway
range too.

Each driving-speed threshold is independent, so tree-fade can stay on
while wall-cutaway has gated off (or vice versa).

### Semantics (computed in `refreshActiveCache`)

1. `!enabled` → both gates `false`.
2. Invalid `IsoCamera.frameState.playerIndex` or null
   `IsoPlayer` → both gates `true` (safe default; vanilla fallback at
   the patch level).
3. Aim-stance gate, with the tree-fade overrides:
   - Cutaway is blocked when `aimStanceOnly && !player.isAiming()`.
   - Tree-fade is blocked when `aimStanceOnly && !player.isAiming()`
     **and** neither override exempts the current context — i.e.
     `!(treeFadeStayOnWhileDriving && inVehicle) && !(treeFadeStayOnWhileOnFoot && !inVehicle)`.
4. Outside a vehicle: any unblocked gate evaluates to `true`.
5. In a vehicle: each unblocked gate is
   `threshold > 0 && |speed| < threshold`. `threshold == 0` means
   "always off in a vehicle" for that feature.

`refreshActiveCache` also writes `currentVehicleSpeedKmh` in the same
pass — so once any tree-fade patch has called the gate for the frame,
`Patch_isTranslucentTree` can read the speed cache without an extra
player/vehicle lookup. `currentVehicleSpeedKmh` is `0f` when not in a
vehicle.

Reads currently-rendering player via `IsoCamera.frameState.playerIndex`,
set by the engine before each per-player render call. In split-screen
each player's pass sees its own value.

### Per-frame memo

Both gates share one cache key `(frameCount, playerIndex)` and two
result slots. `refreshActiveCache` writes both result slots in one
pass.

```java
public static volatile int activeCacheFrameCount = Integer.MIN_VALUE;
public static volatile int activeCachePlayerIndex = Integer.MIN_VALUE;
public static volatile boolean activeCacheCutaway = false;
public static volatile boolean activeCacheTreeFade = false;
```

Every patch in one render pass for one player sees the same snapshot.
Without it `isAiming` or vehicle speed could be re-evaluated across
patches within one frame and flicker at threshold transitions; with
it, all patches agree per frame, with at most one frame of lag at
threshold crossings (visually imperceptible).

Single-slot cache thrashes in split-screen (one slot, two alternating
playerIndices), but a recompute miss is ~10 ops — not worth a
per-player array.

## Logging

`trace(String)` / `trace(String, Throwable)` → `stdout`, prefix
`[PeekAView] `.

## Performance

The `Enable` toggle off path (master switch) leaves zero PeekAView
frames in CPU samples and zero peekaview-attributed allocation
samples. The driving-run baseline measured for 1.2.0 is no longer
representative after the 1.2.1 removal of
`Patch_calculateObjectsObscuringPlayer`; rerun before quoting
specific numbers.
