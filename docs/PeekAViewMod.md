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
| `DEFAULT_RANGE` | 10 | Wall-cutaway slider default. |
| `MIN_TREE_FADE_RANGE` | 5 | Tree-fade lower bound. |
| `MAX_TREE_FADE_RANGE` | 25 | Tree-fade upper bound (independent of cutaway). |
| `DEFAULT_TREE_FADE_RANGE` | 15 | Tree-fade slider default. |
| `TREE_FADE_SNAP_MIN_KMH` | 10f | km/h floor for the fade boost in `Patch_isTranslucentTree`. Below this speed the boost is skipped and vanilla's `alphaStep` owns the fade — without this floor, even tiny `t³` values compound across the 6-10 `isTranslucentTree` calls per tree per frame and the snap feels instant at any non-zero speed. |
| `TREE_FADE_SNAP_SPEED_CAP_KMH` | 80f | km/h cap for the fade boost. Between `MIN_KMH` and `CAP_KMH` the per-call decrement follows a cubic ramp `(speed - MIN) / (CAP - MIN)`; at or above the cap the decrement covers the full 0.75 fade range in one call. |

## State fields

All `public static volatile` — render thread reads; Lua UI thread writes
via setters. No ordering needed beyond single-field visibility; patches
tolerate a one-frame lag.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | boolean | `true` | Master switch. F8 hotkey (rebindable) toggles this. |
| `cutawayEnabled` | boolean | `true` | Wall-cutaway section enable. Gates the extended raster, the `cutawayVisit` dedup, and the B42 fix together. |
| `stairEnabled` | boolean | `true` | Stair view section enable. Gates `Patch_IsoWorld.computeFake` so no `FakeWindow` mutation happens when off. See [`Stair_feature.md`](Stair_feature.md). |
| `aimStanceOnly` | boolean | `false` | Restricts cutaway to aim-state. Tree-fade and stair view are independent of this. |
| `range` | int | `DEFAULT_RANGE` | Wall-cutaway slider value. |
| `cutawayActiveInVehicle` | boolean | `true` | Cutaway runs in vehicles when on; off in any vehicle when off. Replaces the prior km/h slider. |
| `fixB42Adjacency` | boolean | `true` | B42 wall-hiding bug workaround toggle. Gated by `cutawayEnabled` and `enabled` first; independent of `aimStanceOnly`. The fix is gated to outdoor only via `isCameraPlayerIndoor()`; see `Patch_FBORenderCutaways.md`. |
| `fadeNWTrees` | boolean | `true` | Tree-fade section enable. |
| `treeFadeRange` | int | `DEFAULT_TREE_FADE_RANGE` | Tree-fade slider value. |
| `currentVehicleSpeedKmh` | float | `0f` | Per-frame cache (absolute value), written in `refreshActiveCache`. Read by `Patch_isTranslucentTree.exit` for the speed-proportional fade boost. `0f` when not in a vehicle. |
| `isVehicleReversing` | boolean | `false` | Per-frame flag, written in `refreshActiveCache`. `true` when in a vehicle moving backward faster than the 1 km/h dead-zone. Read by `isTileInCameraPlayerCone` to flip the forward direction so the cone follows direction-of-travel when reversing. |
| `currentCameraPlayerConeDot` | float | `-0.2f` | Per-frame cache of the rendering player's vanilla cone, capped at 0.0 so the vehicle override (`cone = 1.0`, 360°) doesn't widen the tree-fade gate beyond forward 180°. Sourced from `IsoGameCharacter.calculateVisibilityData`. |

## Setters

- `setEnabled(boolean)`
- `setCutawayEnabled(boolean)`
- `setStairEnabled(boolean)`
- `setAimStanceOnly(boolean)`
- `setRange(int)` — clamps to `[MIN_RANGE, MAX_RANGE]`; on change calls
  `Patch_IsoCell.Patch_GetSquaresAroundPlayerSquare.invalidateCache()`.
- `setCutawayActiveInVehicle(boolean)`
- `setFixB42Adjacency(boolean)`
- `setFadeNWTrees(boolean)`
- `setTreeFadeRange(int)` — clamps to
  `[MIN_TREE_FADE_RANGE, MAX_TREE_FADE_RANGE]`.

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
tree-fade gate. The two B42-fix patches (`Patch_shouldCutaway`,
`Patch_isAdjacentToOrphanStructure`) do **not** call either gate;
they check `enabled`, `cutawayEnabled`, `fixB42Adjacency`, and
`isCameraPlayerIndoor()` (outdoor-only; see
`Patch_FBORenderCutaways.md`), and otherwise run regardless of stance
or vehicle. Stair view runs through its own gates inside
`Patch_IsoWorld.computeFake`; see [`Stair_feature.md`](Stair_feature.md).

### Semantics (computed in `refreshActiveCache`)

1. `!enabled` → both gates `false`.
2. `!isPeekAViewActive()` → both gates `false` (mod removed from
   active set after JVM start; see [`isPeekAViewActive()`](#ispeekaviewactive)).
3. Invalid `IsoCamera.frameState.playerIndex` or null `IsoPlayer` →
   both gates `true` (safe default; vanilla fallback at the patch
   level).
4. Cutaway: blocked by `!cutawayEnabled || (aimStanceOnly &&
   !player.isAiming())`. Otherwise, in a vehicle follows
   `cutawayActiveInVehicle` (true = on, false = off). On foot, always
   on (subject to the cutawayEnabled / aim-stance gates above).
5. Tree-fade: master toggle (`fadeNWTrees`) is the only gate beyond
   `enabled`, and that check happens in the patch bodies, not in
   `refreshActiveCache`. Aim-stance and vehicle context don't gate
   tree-fade.

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

## Render-state helpers

### `isCameraPlayerIndoor()`

True iff the camera player's tile is in a room
(`IsoCamera.frameState.camCharacterSquare.isInARoom()`). Outdoor-only
gate consumed by:

- `Patch_GetSquaresAroundPlayerSquare` (cutaway extension; indoor
  falls back to vanilla 5-tile raster)
- `Patch_DrawStencilMask` (tree-fade stencil)
- `Patch_isTranslucentTree` (tree-fade renderFlag)
- `Patch_shouldCutaway` and `Patch_isAdjacentToOrphanStructure` (B42 fix)

The stair-view feature is gated indoor-or-outdoor by the same flag in
the inverse direction — see [`Stair_feature.md`](Stair_feature.md) for
its own gate stack (no `isCameraPlayerIndoor()` involvement after
1.4.0).

### `isExternalStairFeatureActive()`

Detects whether an external stair-feature mod is active in the running
save by scanning `ZomboidFileSystem.instance.getModIDs()` for the
strings `"Staircast"` (upstream Workshop mod by copiumsawsed) and
`"StaircastRP"` (the standalone read-path repo at
[`armakupub/staircast-rp`](https://github.com/armakupub/staircast-rp)).
Returns `true` on either match, `false` otherwise or on any `Throwable`
reading the mod list.

The `StaircastRP` match is defensive: PeekAView's `mod.info` declares
`incompatible=StaircastRP`, so PZ's mod manager normally prevents both
from being active in the same save. The runtime match here only fires
if that guard is bypassed (manual mod-list editing, etc.).

Why not `Class.forName`: PZ keeps a single JVM across world reloads,
but mod activation can change between worlds. Once Staircast's
`pzmod.staircast.Mod` class has been loaded by any save in the
current JVM, it stays in the classloader for the rest of the JVM's
lifetime — `Class.forName` keeps resolving successfully even after
the user disables the mod. `getModIDs()` reads the list populated by
the current save's `loadMods()` call, so it tracks runtime activation
correctly. Per-call cost is one ArrayList iteration over a typically
short mod list.

A separate `externalStairFeatureTraceLogged` flag logs once per JVM
lifetime on first true detection so `console.txt` shows a clear
signal that the gate engaged. Detection result is recomputed each
call regardless.

Used by `Patch_IsoWorld.computeFake` as the third hard gate (after
`enabled` and `stairEnabled`) before any `FakeWindow` mutation. See
[`Stair_feature.md`](Stair_feature.md).

### `isPeekAViewActive()`

Self-check against ZombieBuddy's advice-persistence behaviour (filed
upstream as
[zed-0xff/ZombieBuddy#13](https://github.com/zed-0xff/ZombieBuddy/issues/13)):
once ZB scans this mod's `@Patch` classes during the first save load
that activates PeekAView, the woven advice stays in ZB's global
registry for the JVM lifetime, even if PeekAView is later removed from
the active mod set on a subsequent save load without restarting PZ.
This method scans `ZomboidFileSystem.instance.getModIDs()` for
`"PeekAView"` and returns `true` iff present.

Used as an early gate by `refreshActiveCache()` (cutaway / tree-fade)
and by `Patch_IsoWorld.computeFake` (stair view) so a save that no
longer subscribes PeekAView gets vanilla rendering on the next save
reload, rather than continuing to fire the woven advice.

Returns `true` on detection failure (any `Throwable` reading the mod
list) so legitimate sessions never lose features when `getModIDs`
throws.

A separate `peekAViewPhantomTraceLogged` flag logs once per JVM
lifetime on first false detection so `console.txt` shows a clear
signal that the self-deactivation engaged.

### `isTileInCameraPlayerCone(IsoGridSquare sq)`

Forward-cone test:
`(player_pos - tile_center) · player_forward < currentCameraPlayerConeDot + 0.25`.
Per-frame instantaneous, computed directly from
`player.getForwardDirectionX/Y()` — `isCanSee` would lag during
fast rotation and pop close trees in/out.

`currentCameraPlayerConeDot` is refreshed once per frame in
`refreshActiveCache` from `p.calculateVisibilityData().getCone()`,
so the cone inherits vanilla's state:

| State | Vanilla cone | After cap at 0.0 | Threshold (+0.25) |
|-------|-------------|------------------|-------------------|
| Default | -0.2 | -0.2 | +0.05 |
| Eagle-Eyed | +0.0 | 0.0 | +0.25 |
| Fatigued (>0.6) | -0.2 - fatigue·2.5 | unchanged | narrower |
| Panicked (level 4) | additional -0.2 | unchanged | narrower |
| Drunk | additional -INTOXICATION·0.002 | unchanged | slight narrowing |
| In a vehicle | 1.0 | **capped to 0.0** | +0.25 (forward 180° + buffer, not 360°) |

Vanilla's vehicle override (`cone = 1.0`, effectively 360°) is meant
for LOS visibility in driving contexts. For our tree-fade gate we
cap at 0.0 so the cone stays forward-focused — otherwise trees
behind the vehicle would also fade, which doesn't match the "fade
what the player is looking at" intent.

The `+0.25` buffer absorbs float-noise flicker at the cone's
perpendicular boundary (`dot == 0` when `player_forward` is
axis-aligned, which every cardinal walk/drive heading is). At
narrower cones (fatigue, panic) the threshold stays negative so
perpendicular is firmly out — no `dot == 0` boundary case at any
cone state, no per-axis special cases.

Used by `Patch_isTranslucentTree` for the renderFlag flip;
`Patch_DrawStencilMask` keeps `sq.isCanSee` for wall-blocking
(see `Patch_IsoCell.md`).

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
