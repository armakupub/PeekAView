# Stair view feature

Render-time camera uplift while the player stands on stairs, so the
upper floor renders during the climb instead of only after reaching
the top.

The feature is not a single patch but a family of coordinated patches
across the render pipeline. The state lives in `FakeWindow` and
`FakeFrameState` (helper classes in the same package).

## File map

All 10 files sit in `src/pzmod/peekaview/`. Some are dedicated stair
files; two (`Patch_FBORenderCell.java`, `Patch_IsoCell.java`) host
stair `@Patch` inner classes alongside their pre-existing
tree-fade / cutaway inner classes.

| File | Stair role |
|---|---|
| `FakeFrameState.java` | Per-player render-pass state. |
| `FakeWindow.java` | Registry + ThreadLocal coordination + reflective field-write of `IsoMovingObject.x/y/z`. |
| `Patch_IsoWorld.java` | Outer entrypoint. `computeFake` fills `FakeWindow` per frame. |
| `Patch_FBORenderCell.java` | Inner classes `Patch_renderInternal`, `Patch_isPotentiallyObscuringObject`, `Patch_renderPlayers`. (Coexists with tree-fade `Patch_isTranslucentTree`.) |
| `Patch_IsoCell.java` | Inner class `Patch_renderInternal` for the non-FBO path. (Coexists with `Patch_GetSquaresAroundPlayerSquare` and `Patch_DrawStencilMask`.) |
| `Patch_IsoMovingObject.java` | Read-path patches on `getX/getY/getZ/getCurrentSquare`. |
| `Patch_IsoPlayer.java` | Restore real values for non-FBO player-sprite render. |
| `Patch_FBORenderTrees.java` | Restore real values for the tree render pass. |
| `Patch_LightingJNI.java` | Uplift lighting probe to upper floor when the cluster qualifies. |
| `Patch_WeatherFxMask.java` | Uplift weather-FX origin. |
| `Patch_IsoObject.java` | Render-time alpha override + field-write for upper-floor zombies in the player's forward cone. |

## Per-frame sequence

`Patch_IsoWorld.Patch_renderInternal.@OnEnter` runs `computeFake` at
the start of each world render pass.

`computeFake` checks gates in order:

1. `PeekAViewMod.enabled` (master)
2. `PeekAViewMod.stairEnabled` (section toggle)
3. `PeekAViewMod.isPeekAViewActive()` (self-check; yield if PeekAView
   is no longer in the active mod set)
4. `!PeekAViewMod.isExternalStairFeatureActive()` (yield to external
   stair feature if Staircast or StaircastRP is loaded)

On gate failure: return early, no `FakeWindow` mutation.

**Pause handling.** Between the hard gates and the strict checks,
`computeFake` reads `GameTime.isGamePaused()` (PZ public API,
single-source-of-truth across single-player, client, and dedicated-
server modes). If paused **and** a fakeWindow was active on the
immediately previous frame (`fs.frameCount - ffs.frameCounter <= 1`),
`computeFake` bumps `ffs.frameCounter` to the current frame and
returns. No other state is mutated.

Effect: downstream patches see `FakeWindow.isReady()` true and keep
rendering the last fake state. Char + upper floor stay visible across
the pause boundary. The `<= 1` gate ensures only a just-active
fakeWindow gets thawed; an old fakeWindow from a previous unrelated
activation falls through to the normal flow.

Why this is needed: PZ decouples render-thread from game-thread
during pause. `IsoCamera.frameState.frameCount` keeps advancing
(render-thread runs to keep the UI responsive), but the game-thread
freezes. Frame-counter-based hysteresis windows (`recentlyActive`,
`ascendingRecently`) all expire in 30 frames (~0.5 s), at which point
the strict checks would return early without updating
`ffs.frameCounter` — `isReady` goes false, and the downstream render
patches fall through to vanilla. Without this freeze, mid-climb pause
snaps from "stair-view active" to vanilla within half a second,
blanking both the upper floor and the char sprite.

On gate pass: strict checks for the rendering player — `IsoCamera.getCameraCharacter()`
non-null, `IsoCamera.frameState.camCharacterSquare` non-null, not in a
vehicle, has an active model, on a stair tile (`HasElevatedFloor`),
look-angle within the stair cone, viewpoint Z (head bone position) at
or above the upper-floor target.

A 30-frame hysteresis window keeps the fake-window open through brief
soft-check failures (cone or head-Z wobble during the stair animation).
`FakeFrameState.lastStrictActivationFrame` records the last frame where
all checks passed strictly; subsequent frames within the window stay
active using last-frame's `floorSquare` / `fakeSquare` if the current
frame can't compute fresh ones.

**Stair-tile latch.** The 30-frame hysteresis alone is too short to
bridge the entire upward climb — strict-pass fails repeatedly through
the head-bone Z animation, and once `recentlyActive` runs out the fake
window snaps shut mid-climb. A per-`FakeFrameState` latch holds the
window open through those longer gaps without introducing a hardcoded
climb-duration budget that would break with TIS animation-speed
changes.

State on `FakeFrameState`:

- `stairLatchArmed` — set when strict-pass succeeds while the camChar
  is on a stair tile (`IsoGridSquare.HasStairs()`). Cleared as soon as
  the camChar steps off all stair tiles.
- `peakCharZ` — the highest `charZ` seen since the latch armed. Reset
  to current `charZ` on first arm of a new climb.
- `lastZIncreaseFrame` — frame index at which `peakCharZ` was last
  advanced.

Each `computeFake` while on a stair tile and armed:

1. If `charZ > peakCharZ`: update `peakCharZ` and `lastZIncreaseFrame`.
2. Else if `peakCharZ - charZ > 0.05f`: clear `stairLatchArmed`. The
   threshold is a fraction of the stair Z scale (5% of 1.0), not a
   frame budget — robust to animation-speed changes.

The latch contributes to keeping the window open only when the player
has been actively rising recently:

```java
boolean ascendingRecently = stairLatchArmed
    && (frameCount - lastZIncreaseFrame) <= HYSTERESIS_FRAMES;
boolean stairLatch = onStair && ascendingRecently && stairLatchArmed;
```

Why the recency window matters: animation key-poses inside a single
climb have stationary frames where `peakCharZ` doesn't advance.
A frame-by-frame `charZ > lastCharZ` ascending check rejects those and
caused the fake window to flicker shut for one frame at a time,
toggling wall cutaway and snapping zombie alpha. The 30-frame
HYSTERESIS_FRAMES budget bridges those pauses while still releasing
the latch on a sustained no-progress period (turn-around without
descent).

Release conditions:

- **Off-stair**: camChar steps off all stair tiles → latch cleared
  immediately. Falls back to the standard 30-frame strict-pass
  hysteresis, which closes the window within ~0.5 s of leaving the
  stairs.
- **Sustained stationary**: `peakCharZ` not advanced for >30 frames →
  `ascendingRecently` becomes false → latch contributes nothing →
  again falls back to the strict-pass hysteresis. Restores the
  original Staircast behavior of "turn around fades the upper floor"
  that the unconditional latch had erased.
- **Active descent**: `peakCharZ - charZ > 0.05f` → `stairLatchArmed`
  cleared on the same frame. No multi-frame ramp — the moment the
  drop is detected, the latch yields and the window closes through
  the hysteresis path.
- **Landing arrival**: when the camChar steps onto a tile with
  `hasFloorAtTopOfStairs() == true` whose Z matches `fakeSquare.z`,
  the climb has reached its destination. `lastStrictActivationFrame`
  and `lastZIncreaseFrame` are reset to `-1` and the local
  `recentlyActive` is force-cleared the same frame, so the fake
  window deactivates immediately instead of dragging the upper-floor
  view through the 30-frame hysteresis after arrival. Without this
  clip the room behind a corner stair flashes briefly into view as
  the player walks off the top step.

The gate at `if (!strictPass && !recentlyActive && !stairLatch) return`
combines all three keep-open paths. Strict-pass is itself gated on
`onStair` upfront so a regular indoor floor tile under an upper floor
(`HasElevatedFloor=true` on most building tiles) cannot keep
`lastStrictActivationFrame` refreshing — the feature activates only
on stair tiles.

On full pass, `FakeWindow.data[playerIndex]` is filled with:

- `realPos` / `realSquare` — the player's actual world position
- `fakePos` / `fakeSquare` — the upper-floor target (`z + 1`),
  preferred to be the landing the player walks onto rather than the
  cell directly above the stair tile (those differ for stairs that
  end at a building corner; the cell above can sit one tile inside
  the room and would render the interior visible mid-climb)
- `floorSquare` — the upper-floor anchor whose `room` / `roomId` /
  `exterior` flags are inherited onto `fakeSquare` during the swap
- `frameCounter` — `IsoCamera.frameState.frameCount`
- `lastViewpointZ` — head-bone Z, snapped onto last-frame's value if
  delta < 0.02
- `renderLighting` — `true` iff the tile is a stair-top and no zombie
  sits on a neighboring stair tile
- `lastStrictActivationFrame` — bumped to current frame on strict
  pass

## Render-pass swap (FBO)

`Patch_FBORenderCell.Patch_renderInternal.@OnEnter` runs at the start
of each chunk's render. If `FakeWindow.isReady(playerIndex)` (frame
counter matches), it:

1. Saves the real `IsoCamera.frameState.camCharacterX/Y/Z` and
   `camCharacterSquare`.
2. Commits `opened = true` and calls `FakeWindow.renderingFake.set(ffs)`
   right after the captures. Any throw further down still hits the
   exit cleanup path, with each cleanup branch gated on its own
   step-flag (`currentSwapped`, `posMutated`, `sqSwapped`) so only the
   mutations that actually landed get reversed.
3. Overwrites the frame state with `ffs.fakePos.x/y/z` and `ffs.fakeSquare`.
4. Saves the camChar's `getCurrentSquare()`, calls
   `setCurrent(ffs.fakeSquare)`, sets `currentSwapped = true`.
5. Sets `FakeWindow.fieldMutated.set(playerIndex, 1)`, then reflectively
   writes `ffs.fakePos.x/y/z` onto the camChar's private
   `IsoMovingObject.x/y/z` fields via `FakeWindow.writeFakePos`. The
   flag-before-write order is load-bearing — see "Cross-thread
   ordering" below. Sets `posMutated = true` on success; rolls the
   flag back to 0 on Reflection failure.
6. If `fakeSquare.room == null` and `floorSquare.room != null`, saves
   the original `room` / `roomId` / `exterior` flag, sets
   `sqSwapped = true` (committed *before* the actual mutation so a
   partial throw mid-swap still gets cleaned up), then writes
   `floorSquare.room`, `floorSquare.roomID`, and unsets `exterior` if
   it was set.

The chunk now renders as if the player were on the upper floor.

`@OnExit` reverses every step. It reads the `FakeFrameState` via
`FakeWindow.get(idx)` rather than the ThreadLocal — a nested
inverted-pair patch (renderPlayers, FBORenderTrees) may have cleared
the ThreadLocal mid-window:

- Restore the saved camera/square values onto frame state.
- If `posMutated`: `FakeWindow.writeRealPos(...)` first, then
  `FakeWindow.fieldMutated.set(idx, 0)`. Reverse de-mutate order is
  load-bearing too — during the gap a non-render reader sees flag
  still 1 and gets `realPos.x` (matching the now-real field).
  Clearing the flag first would briefly expose the fake field via the
  vanilla getter.
- If `currentSwapped`: `setCurrent(savedCurrent)`.
- If `sqSwapped`: restore `fakeSquare.room` / `roomId` / `exterior`.
- `finally`: `FakeWindow.renderingFake.remove()`.

`Patch_IsoCell.Patch_renderInternal` mirrors this for the non-FBO
render path. `PerformanceSettings.fboRenderChunk` decides which path
the engine takes; only one runs per frame.

## Player sprite at real position (inverted swap)

When it's the player sprite's turn to draw inside the fake-render
window, `Patch_FBORenderCell.Patch_renderPlayers.@OnEnter` (FBO) and
`Patch_IsoPlayer.Patch_render.@OnEnter` (non-FBO; `if
(PerformanceSettings.fboRenderChunk) return;`) form the inverted pair:

1. Save the current (fake) camera/square values and the ThreadLocal
   `renderingFake` pointer. Commit `paused = true` immediately after
   the captures so any throw downstream still hits the exit re-mutate
   path.
2. Overwrite frame state with `ffs.realPos` / `ffs.realSquare`.
3. `setCurrent(ffs.realSquare)`, then `FakeWindow.writeRealPos(...)`,
   then `FakeWindow.fieldMutated.set(idx, 0)`. Field-write before
   flag-clear so the gap stays safe (flag still 1, shadow returns
   `realPos.x` — matches the now-real field).
4. `renderingFake.remove()`.

The player sprite renders at the real world position (on the stair
tile). On `@OnExit`, the fake state is restored: `setCurrent` back to
the fake square, then `FakeWindow.fieldMutated.set(idx, 1)` BEFORE
`writeFakePos` (same flag-before-write ordering as the outer enter
path), with rollback to 0 on Reflection failure. Finally the
ThreadLocal pointer is restored.

`Patch_FBORenderTrees.Patch_init.@OnEnter` does the same inverted
restore for the FBO tree render pass — trees draw at their real world
Z so their sprites don't visually jump up to the upper-floor plane.

## Read-path shadow

`Patch_IsoMovingObject` patches `getX`, `getY`, `getZ`, and
`getCurrentSquare`. Three modes:

- **Render thread**, ThreadLocal `renderingFake` set, `self ==
  ffs.camChar`: return the fake value (`ffs.fakePos.x` / `.y` / `.z`
  or `ffs.fakeSquare`).
- **Non-render thread**, `renderingFake` is null but
  `FakeWindow.findMutatedFor(self)` returns a non-null `ffs` (i.e. the
  outer render pass is still in its window and has reflectively
  mutated `self.x/y/z`): return the saved REAL value
  (`ffs.realPos.x` / `.y` / `.z` or `ffs.realSquare`).
- **Otherwise**: return `false` (skip), vanilla getter returns
  `this.x` field as usual.

The non-render-thread shadow is the load-bearing piece. PZ's
`IsoGameCharacter.updateFalling` and helpers (`getHeightAboveFloor`)
run on the game thread and read both `this.current` and `getZ()`.
During the brief render window where the private `x/y/z` fields are
reflectively mutated, an unshadowed game-thread read would see
fake-Z. `updateFalling` would compare against fake-Z, conclude the
player is falling from the upper floor, and trigger an infinite
stair-fall loop documented in the upstream Staircast issue tracker.

## Lighting and weather uplift

`Patch_LightingJNI.Patch_updatePlayer.@OnEnter` and
`Patch_checkPlayerTorches.@OnEnter` swap to fake values when both:

- `FakeWindow.isReady(playerIndex)`
- `FakeWindow.get(playerIndex).renderLighting == true`

`renderLighting` is set in `computeFake` only when the tile is a
stair-top (`stairsTN` or `stairsTW`) and no zombie sits on a
neighboring stair tile. Effect: the upper-floor room is lit using the
upper-floor's lighting state.

`Patch_WeatherFxMask.Patch_initMask.@OnEnter` and
`Patch_renderFxMask.@OnEnter` swap to fake values for the weather FX
pass so rain/snow renders at the upper-floor reference plane and
doesn't clip through the rendered upper floor.

## Why the field write at all

A ThreadLocal-only read-path would already shadow `getX/Y/Z` calls
that go through the patched getter. But PZ's render code mixes
getter calls with direct-field reads on the `x/y/z` fields (e.g.
`IsoGameCharacter.getHeightAboveFloor`, `IsoCell.IsCutawaySquare`).
Direct-field reads bypass the getter patch and would see the real
field values for an entire frame while render code via the getter
sees fake — a per-frame visual instability ("ghost jumping" cutaway
flicker on stairs) that hysteresis on the activation gate doesn't
fix.

The field write makes all reads see fake DURING the render window.
The read-path shadow then re-isolates the game thread by returning
the saved real value when `fieldMutated.get(idx) == 1` and the
calling thread is not the render thread (no ThreadLocal set).

`writeFakePos` only touches `x`, `y`, `z` — not `nx`, `scriptnx`,
`lx`, `ly`, `lz` — so stair-climb prediction and interpolation logic
on the game thread stay consistent with the real position.

## Cross-thread ordering

Two pieces of mechanics make the read-path shadow correct under load.

`FakeWindow.fieldMutated` is an `AtomicIntegerArray`, not a
`boolean[]`. Array elements are never volatile even if the reference
is. Without a release/acquire edge between writer and reader, a
non-render thread could read the flag still 0 after the render thread
set it to 1, miss the shadow, and observe the fake field via the
vanilla getter. The acquire on `fieldMutated.get(i)` also publishes
the FakeFrameState mutations done by `computeFake` earlier in the
frame, so a non-null `findMutatedFor` result has consistent
`realPos` / `camChar` fields.

The flag is set BEFORE `writeFakePos` on every mutate path
(renderInternal enter, renderPlayers exit, FBORenderTrees init exit,
IsoPlayer render exit). With the original write-then-flag order, a
non-render thread reading `getX()` between `writeFakePos` and the
flag set saw fake-Z via the vanilla getter (shadow not active yet) —
exactly the read that `updateFalling` does, and the trigger for the
infinite stair-fall loop. Pre-setting the flag means that during the
gap the shadow returns `realPos.x` (which still matches the real
field, since `writeFakePos` hasn't landed yet); after the field
write lands, the shadow keeps returning `realPos.x` while
render-path reads via TL get `fakePos.x`. On Reflection failure the
flag is rolled back to 0.

Symmetrically on the de-mutate path (renderInternal exit and the
inverted enters), `writeRealPos` runs BEFORE `fieldMutated.set(idx, 0)`.
The gap is safe: flag still 1, shadow returns saved `realPos.x` —
which matches the now-real field. Reversing the order would briefly
expose the still-fake field to a non-render reader.

## Zombie alpha override (1.4.0)

`Patch_IsoObject.Patch_getAlpha` extends the stair feature beyond
upstream Staircast: while the fake window is open, zombies on the
upper floor that fall inside the player's forward cone render at full
alpha, even though the engine's LOS pass — which uses real player
position on the stair — has not marked the upstairs squares visible.

Without the override the upper floor renders correctly but its
zombies stay invisible (or briefly flash and fade) because vanilla
`alpha[playerIndex]` decays toward zero whenever the LOS bit on a
square is false. The render reads the cached low alpha and the
zombie sprite never reaches visibility.

The patch shadows `IsoObject.getAlpha(int)`. Conditions for override
(all must hold):

1. `self instanceof IsoZombie` — players, items, and other moving
   objects are not affected.
2. `FakeWindow.isReady(IsoCamera.frameState.playerIndex)` — fake
   window is active for the rendering player **this frame**. Frame-
   based, not thread-based: `getAlpha` may be called from
   `ModelSlotRenderData.init` on a setup thread where the
   `renderingFake` ThreadLocal isn't set, so the standard read-path
   ThreadLocal pattern doesn't catch all readers.
3. `self.square.z == ffs.fakeSquare.z` — only zombies on the upper
   floor's z-layer.
4. `PeekAViewMod.isTileInCameraPlayerCone(self.square)` — the
   zombie-side forward-cone test. Same dot-product math as the
   tree-fade cone but with the cone-dot capped at `0.0`, so the
   gate stays at forward 180° in vehicles instead of opening to
   360°. Zombies behind the camChar (turn-around case) fall back
   to vanilla LOS alpha and fade out naturally.

When all conditions hold the patch returns `1.0f` from `getAlpha`
**and** writes `setAlpha(playerIndex, 1.0f)` on the same call. The
field write is the load-bearing piece for smooth fade-out: when the
zombie subsequently leaves the cone (the player turns, animation
head-bob shifts the look angle, or the climb completes), the next
`getAlpha` call returns vanilla. With LOS-blocked upstairs squares
that vanilla value is zero, which without the field write would
produce a hard snap from visible to invisible. Because `alpha[]`
was bumped to 1.0 each frame the override fired, the engine's
game-thread `updateAlpha` decays the field smoothly toward its real
target — once the override stops writing, `alpha[]` drifts down
naturally and the render reads a smooth fade.

Cross-thread safety: float writes on `alpha[]` are atomic on every
JVM in practice. The field is purely render-presentation, no
gameplay state — game-thread reads (AI behavior, sound, networking)
do not consult per-player alpha. Worst case the game thread sees a
1.0 for a tick and computes one slightly wrong decay step, which
self-corrects the next tick.

The cone gate restores the "see what your character sees" theme on
the upper floor: zombies in the player's forward view become visible
so the climb is informative, but the upstairs is not an X-ray
overview — looking the wrong way still keeps things hidden.

## Compatibility with external stair-feature mods

`PeekAViewMod.isExternalStairFeatureActive()` probes the live save's
active mod list via `ZomboidFileSystem.instance.getModIDs()` and
returns `true` iff either `"Staircast"` (upstream Workshop mod by
copiumsawsed) or `"StaircastRP"` (the standalone read-path repo at
`armakupub/staircast-rp`) appears in the list. PeekAView's stair
feature yields entirely on a true result so the stair-render stacks
do not run in parallel.

Why not `Class.forName("pzmod.staircast.Mod", false, classloader)`:
PZ keeps a single JVM across world reloads, but mod activation can
change between worlds (the user can toggle upstream Staircast on or
off in the mod list and reload). Once Staircast's `pzmod.staircast.Mod`
class has been loaded by any save in the current JVM, it stays in
the classloader for the rest of the JVM's lifetime — `Class.forName`
keeps returning successfully even after the user has disabled the
mod. The next save would then see PeekAView yielding to a Staircast
that isn't actually running, and the stair feature would silently
do nothing. `getModIDs()` reads the list populated by the current
save's `loadMods()` call, so it tracks runtime mod activation
correctly across reloads.

This is the same JVM-scoped advice-persistence behaviour tracked
upstream as
[zed-0xff/ZombieBuddy#13](https://github.com/zed-0xff/ZombieBuddy/issues/13).
PeekAView's own self-check (`isPeekAViewActive()`) covers the inverse
case: when PeekAView itself was active earlier in the JVM but is no
longer in the current save's mod list.

A separate `externalStairFeatureTraceLogged` flag logs once per JVM
lifetime on first true detection so `console.txt` shows a clear
signal that the gate engaged. The detection result itself is
recomputed each call.

## Mutual incompatibility with StaircastRP

The standalone `StaircastRP` mod (same author, source-available at
[`armakupub/staircast-rp`](https://github.com/armakupub/staircast-rp))
is mutually marked incompatible with PeekAView via `mod.info`
`incompatible=` declarations on both sides. PZ's mod manager prevents
enabling both at once, since both register `@Patch` advices on the
same render-path classes and would step on each other's reflective
field writes and ThreadLocal state.

The yield-on-detect in `Patch_IsoWorld.computeFake` covers the
upstream Staircast (which is not under our control), while
`mod.info incompatible=` covers StaircastRP (which is).

## Attribution

- **Cutaway-on-stairs idea + FakeFrameState pattern + choice of patched render classes**: [copiumsawsed/pz-Staircast](https://github.com/copiumsawsed/pz-Staircast) (MIT, original Workshop mod).
- **Read-path implementation** (reflective `x/y/z` field-write that skips `setX/Y/Z`'s side-effects on `nx`/`scriptnx` + ThreadLocal-gated read-path shadow on `IsoMovingObject.getX/Y/Z/getCurrentSquare`): first published as our standalone fork [armakupub/staircast-rp](https://github.com/armakupub/staircast-rp) (MIT).
- **PeekAView extensions on the staircast-rp foundation** (this document):
  - Stair-tile latch (`peakCharZ` + `lastZIncreaseFrame` + `ascendingRecently`) — bridges animation key-pose stationary frames during the climb without flickering.
  - Cone-vision zombie alpha override (`Patch_IsoObject.Patch_getAlpha`) — zombies on the upper floor in the player's forward cone become visible during the climb, not only at the last step.
  - Smooth fade-out via `setAlpha` field-write — vanilla `updateAlpha` decay handles the transition instead of a 1.0→0 hard snap.
  - getModIDs-based external-stair detection (`isExternalStairFeatureActive()`) — replaces `Class.forName`, robust against ZombieBuddy advice persistence.
  - `isPeekAViewActive()` self-check — self-deactivates on save reload after mod-list removal ([ZombieBuddy#13](https://github.com/zed-0xff/ZombieBuddy/issues/13) mitigation).
  - Pause-resistant fake-window freeze — keeps state across the in-game pause boundary using `GameTime.isGamePaused()`.
  - Multi-patch ordering fixes — flag-set BEFORE reflective `writeFakePos` (closes a sub-µs window where `updateFalling` could see fake-Z and trigger the infinite stair-fall loop).
  - Strict-pass `onStair`-gate upfront — prevents `lastStrictActivationFrame` refresh on non-stair indoor floor tiles.
