# PeekAView — Technical Notes

Contributor reference. This file only records what the source cannot
say itself — engine facts, cross-file coordination, and why-not-the-
obvious-way decisions. Everything site-specific lives as comments in
the code.

## Architecture

Three independent features (wall cutaway, tree fade, stair view)
implemented as ZombieBuddy `@Patch` bytecode patches on PZ render
classes. Java owns all runtime state (`PeekAViewMod` statics,
`FakeWindow` for stair view); the Lua layer (`PeekAView_Options.lua`,
`PeekAView_Keybind.lua`) is UI + persistence only and writes through
setters.

Constraints that shape the layout:

- All patch classes sit flat in `pzmod.peekaview` — ZB's `@Patch`
  scanner matches `mod.info javaPkgName=` exactly; classes in
  sub-packages are silently ignored.
- `@Patch` advice is inlined into the target class's bytecode: any
  non-constant field or helper method the advice touches must be
  `public`, or the hot path throws `IllegalAccessError` and the patch
  silently degrades. Compile-time constants may stay private (javac
  folds them into literals).
- Runtime activation checks (`isPeekAViewActive`,
  `isExternalStairFeatureActive`) read
  `ZomboidFileSystem.getModIDs()` instead of `Class.forName`: PZ
  keeps one JVM across world reloads, so classes stay resolvable for
  the JVM lifetime while the active mod list changes per save. Same
  root cause as ZB advice persistence
  ([ZombieBuddy#13](https://github.com/zed-0xff/ZombieBuddy/issues/13)).
- Kahlua reaches `@Exposer.LuaClass` classes only by simple name
  (`PeekAViewMod`); package paths error with `non-table: null`.

## Engine geometry

Ground truth behind the quadrant decisions:

- World coords: `x` east, `y` south, `z` floor level. Iso projection:
  `screenX = (x − y)·32·scale`,
  `screenY = (x + y)·16·scale + (screenZ − z)·96·scale`.
  The camera sits SE-above looking NW: +x renders down-right, +y
  down-left, origin at the top of the screen.
- Render order is `x + y` ascending (anti-diagonal); late draws sit
  on top. A tree T can occlude the player P only if
  `T.x + T.y > P.x + P.y` — SE quadrant always, NE/SW partially, NW
  never (though tall NW sprites still cover screen area above the
  player).
- A sprite belongs entirely to its base tile and extends up-screen
  from it — 3–7 tile-heights for tall trees.

## Wall cutaway

- Vanilla's per-square occlusion data
  (`LazyInitializeSoftOccluders`) projects diagonally SE through
  Z-levels to ~14 tiles. The range slider therefore has
  direction-dependent visible effect: toward SE it duplicates
  vanilla's own reach until ~slider 14, toward N/W each +1 adds +1
  tile of trigger range.
- `cutawayVisit` dedup keys on `(frameCount, playerIndex)`, not the
  `currentTimeMillis` argument: Windows' default 15.625 ms timer
  tick can give two consecutive 60-fps frames the same millisecond,
  which skipped the population call and produced 1-frame cutaway
  dropouts.

### B42 adjacency bug (still unfixed in vanilla as of 42.20)

Player-built structures near vanilla buildings make upper-floor
vanilla walls not render at all. Two vanilla mechanisms combine:

1. `OrphanStructures.shouldCutaway` reads a cell-GLOBAL
   `occludedByOrphanStructureFlag`, OR-accumulated across every POI —
   one player-built cluster anywhere on screen flips `playerInRange`
   for all on-screen clusters (further amplified by the extended POI
   raster).
2. `isAdjacentToOrphanStructure` fans the orphan flag
   8-directionally onto neighboring tiles without re-applying the
   drop-Z anchor test that excludes vanilla walls from orphan marking
   itself — the wall next to a player-built stair is then culled by
   `shouldRenderBuildingSquare`'s orphan-adjacency branch.

Patching `shouldRenderBuildingSquare` directly is not viable: it has
three indistinguishable `return false` paths, and flipping its exit
also neutralizes legitimate building cutaway (confirmed
experimentally — every wall of every approached building vanished).
The two surgical patch sites and their distance / hoppable /
climb-stab layering are documented in `Patch_FBORenderCutaways.java`.
The workaround is opt-in (default off): it alters rendering of
player-built tiles at vanilla facades even for players the bug never
hits.

## Tree fade (42.20)

Vehicle-only complement to vanilla 42.20's own tree fade (SE quadrant
plus aim-gated cursor/player stencil masks). Display is rerouted onto
vanilla's `transparent` path instead of extending the stencil mask —
mask extension stamps a visibly oversized dither circle around the
car. Gates and reroute mechanics in `Patch_FBORenderTrees.java`,
range/snap classification in `Patch_FBORenderCell.java`.

## Stair view

Render-time camera uplift while climbing stairs. Per-frame flow:

1. `Patch_IsoWorld.computeFake` (on `IsoWorld.renderInternal` enter)
   runs the hard gates (enabled, stairEnabled, self-check,
   external-stair yield), pause freeze, strict activation checks,
   hysteresis + stair latch, and fills the per-player
   `FakeFrameState` in `FakeWindow`.
2. `Patch_FBORenderCell.Patch_renderInternal` (FBO) or
   `Patch_IsoCell.Patch_renderInternal` (non-FBO) swaps
   `IsoCamera.frameState` and the camChar's `current` square, and
   reflectively writes fake `x/y/z` for the chunk render.
3. Nested inverted pairs restore real values mid-window:
   `Patch_renderPlayers` / `Patch_IsoPlayer.render` (player sprite at
   real position), `Patch_FBORenderTrees.Patch_init` (trees at real
   Z), `Patch_IsoGameCharacter.renderlast` (halo/nametag overlays).
4. `Patch_LightingJNI` and `Patch_WeatherFxMask` uplift lighting and
   weather FX to the fake plane. `Patch_IsoCell.Patch_update` and the
   `current`-revert inside `Patch_WeatherFxMask` guard the two known
   readers that bypass the getter shadow (`updateWeatherFx` reading
   `camCharacterSquare`, `getMasterRegion` reading the `current`
   field).
5. `Patch_IsoMovingObject` shadows `getX/Y/Z/getCurrentSquare`:
   render thread with ThreadLocal set → fake; background threads
   during the mutation window → saved real; otherwise vanilla.
6. `Patch_IsoObject.Patch_getAlpha` makes upper-floor zombies inside
   the forward cone visible during the climb, gated to the landing
   room (plus staircase tiles and the landing's immediate ring) so
   zombies in neighbor rooms stay on vanilla LOS alpha;
   `Patch_UIManager.getTileFromMouse` +
   `Patch_IsoCell.Patch_doBuildingInternal` keep the WalkTo click
   target consistent with the fake-Z projection.

Why the reflective field write exists at all, and the load-bearing
flag-before-write / write-before-flag ordering on every (de)mutate
path, are documented in `FakeWindow.java`.

## Build / deploy

`bash build.sh` — compiles against the PZ + ZB jars (Zulu JDK under
`tools/`), packages `peekaview.jar`, installs to
`~/Zomboid/mods/PeekAView`, and syncs the Workshop stage if
`WORKSHOP_STAGE_MOD` is set in `build.local`. Aborts if PZ is running
(locked JAR would leave the deploy half-done).

Split-screen is untested on real hardware.
