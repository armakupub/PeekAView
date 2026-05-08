# Patch_FBORenderCutaways

Three patches on `zombie.iso.fboRenderChunk.FBORenderCutaways` and its
`$OrphanStructures` inner class.

**File:** `src/pzmod/peekaview/Patch_FBORenderCutaways.java`

## Patch 1 — `cutawayVisit` dedup

**Patched method:** `FBORenderCutaways.cutawayVisit`

### Problem

`FBORenderCutaways.doCutawayVisitSquares(int, ArrayList)` loops every
POI and calls
`cutawayVisit(poiSquare, currentTimeMillis, onScreenChunks)` for each.
`cutawayVisit` walks every cutaway wall on-screen and, per tile, calls
`getCutawayDataForLevel` + `shouldRenderSquare` + `IsCutawaySquare` +
`HashSet.contains` on `cutawayVisitorVisitedNorth/West`.

`cutawayVisit`'s output does not depend on `poiSquare`:
- `IsCutawaySquare` uses `poiSquare` only in (a) a z-check against
  `square` which is tautological (`square` is fetched with `poiSquare.z`
  as z), and (b) a null-and-building if-block whose body is empty.
- Return value fully determined by
  `(wall, square, playerIndex, cell state)`.

On every call after the first within one `doCutawayVisitSquares` pass,
every touched square is already in `visitedNorth/West`; the `bVisited`
guard makes `IsCutawaySquare` unreachable, but the per-tile
`getCutawayDataForLevel` + `shouldRenderSquare` + `HashSet.contains`
still run. Pure waste and the single biggest downstream cost when an
extended range inflates the POI list.

### Fix

Skip all-but-first call per `(frameCount, playerIndex)` tuple.

**Frame identity source:** `IsoCamera.frameState.frameCount` — monotonic,
incremented once per render frame in `GameWindow`. Same field vanilla
uses for "once-per-frame" patterns (e.g. `IsoGridSquare.splashFrameNum`,
`IsoChunkLevel.rainSplashFrameNum`). Unique per render frame,
independent of timer resolution, wraps only after ~414 days at 60 fps.
Split-screen players share `frameCount` but differ on `playerIndex`, so
their tuples are distinct.

**Earlier iteration used `currentTimeMillis` arg — abandoned:** Windows
default timer tick is 15.625 ms, so two 16.67 ms frames can capture the
same millisecond, making the second pass skip vanilla entirely, leaving
`cutawayVisitorResultsNorth/West` empty and producing a 1-frame cutaway
dropout. `frameCount` has no such failure mode.

**Correctness:** first call in a pass populates `visitedNorth/West` and
`resultsNorth/West`. Subsequent vanilla calls would only re-traverse the
same walls, rediscover visited squares, add nothing to results
(`bVisited` short-circuits append). Skipping them yields identical
`PerPlayerData` state.

### State

```java
public static int lastPlayerIndex = Integer.MIN_VALUE;
public static int lastFrameCount = Integer.MIN_VALUE;
```

`public` for the advice-inlining access context (same rule as
`Patch_IsoCell`).

### Enter

`@Patch.OnEnter(skipOn = true)`:

```
if (!isActiveCutawayForCurrentRenderPlayer()) return false   // vanilla
if (pIdx == lastPlayerIndex && fCount == lastFrameCount) return true   // skip
lastPlayerIndex = pIdx; lastFrameCount = fCount
return false   // first call this pass — run vanilla
```

---

## Patch 2 — `OrphanStructures.shouldCutaway` distance gate

**Patched method:**
`FBORenderCutaways$OrphanStructures.shouldCutaway`

### Problem

Vanilla `shouldCutaway` reads a GLOBAL cell flag
`occludedByOrphanStructureFlag`. The flag is OR-accumulated in
`FBORenderCutaways.CalculateBuildingsToCollapse` across every POI in
`pointOfInterest`. Each POI calls
`IsoCell.GetBuildingsInFrontOfCharacter`, whose projection fan scans for
player-built floors above. Once any POI's fan hits one, the flag is set
and vanilla's `shouldCutaway` returns `true` for every
`OrphanStructures` cluster on-screen at `level > camZ`. Every such
cluster flips to `playerInRange=true`, and the
`shouldRenderBuildingSquare:682` adjacency check hides vanilla
upper-floor walls adjacent to any player-built cluster.

Two symptoms collapse into this one mechanism:
1. **Vanilla B42 adjacency-kill.** Player in a 1-tile gap between a
   vanilla wall (y+0) and player-built stair (y+2) — player's own POI
   fan hits the orphan floor above → flag set → nearby wall hides.
2. **Extended-range amplification.** Our expanded POI list makes POIs
   20 tiles away also run `GetBuildingsInFrontOfCharacter`, letting any
   of them set the flag. Effect: walls hide at ~20-tile range on the
   same facade.

### Fix

Per-cluster distance gate. Override `shouldCutaway` result to `false`
when the cluster has no orphan tile within `RADIUS_TILES = 6` of the
currently rendering player. Matches the intent of the vanilla feature
(cutaway so the player on a player-built structure stays visible)
without the bleed.

### Advice

`@Patch.OnExit` — reads the result and writes it back if we override:

```java
@Patch.Return(readOnly = false) boolean result
```

Early returns if:
- `!result` (vanilla already said no — nothing to override)
- `!PeekAViewMod.enabled` (master switch off)
- `!PeekAViewMod.cutawayEnabled` (cutaway section disabled — the B42
  fix is part of the cutaway category and is gated together)
- `!fixB42Adjacency` (user toggle off)
- `PeekAViewMod.isCameraPlayerIndoor()` — outdoor-only gate, see
  [Why outdoor only](#why-outdoor-only) below.
- `!initialized` after `tryInit()`

### Field reflection

`OrphanStructures.chunkLevelData` and `isOrphanStructureSquare` are
package-private. Resolved via ZombieBuddy's `Accessor` once on first use
and cached in:

```java
public static volatile Field FIELD_CHUNK_LEVEL_DATA;
public static volatile Field FIELD_IS_ORPHAN_SQUARE;
public static volatile boolean initialized;
public static volatile boolean initFailed;
```

### `isTooFarFromPlayer(orphanStructures)`

```
mask = get isOrphanStructureSquare (long)
if mask == 0 → true
cld  = get chunkLevelData
chunk = cld.levelsData.chunk
px, py = IsoCamera.frameState.camCharacterX/Y (fastfloor)
cwx, cwy = chunk.wx * 8, chunk.wy * 8
for each bit idx set in mask:
    wx = cwx + (idx & 7)
    wy = cwy + (idx >>> 3)
    if (wx-px)² + (wy-py)² <= RADIUS_TILES² → false
return true
```

`mask` is a 64-bit bitmask over the chunk's 8×8 grid (`idx & 7` = x,
`idx >>> 3` = y). Per-cluster walk is at most 64 bits — cheap.

---

## Patch 3 — `OrphanStructures.isAdjacentToOrphanStructure`

**Patched method:**
`FBORenderCutaways$OrphanStructures.isAdjacentToOrphanStructure`

### Why not patch `shouldRenderBuildingSquare` directly

`shouldRenderBuildingSquare` has three `return false` paths:
1. `buildingsToCollapse` — normal cutaway/collapse (wall hides when
   player approaches a building).
2. Basement (`playerZ < 0`).
3. Orphan-adjacency — **the broken one**.

Only path (3) is buggy: vanilla's adjacency fan hits a legitimate
vanilla wall whose southern neighbor is an orphan (player-built
stair/floor). The wall isn't orphan itself (vanilla's
`calculateOrphanStructureSquare` correctly excludes it via the drop-Z
anchor test), but the fan doesn't apply that same gate.

Patching `shouldRenderBuildingSquare` with an exit override can't
distinguish the paths from outside — flipping every `false` to `true`
would also neutralize path (1), killing normal cutaway. Observed: when
we patched at that level, approaching a house from NW made every wall
vanish.

### Why `isAdjacentToOrphanStructure` is the surgical point

Called from exactly one site: `shouldRenderBuildingSquare:682`, the
orphan-adjacency path. Nowhere else in the engine.

Flipping its `true → false` when the **queried** square is anchored to
a real vanilla building:
- leaves path (1) `buildingsToCollapse` untouched (different branch)
- leaves path (2) basement untouched (different branch)
- only neutralizes path (3) for real-building tiles
- leaves real orphan tiles alone — they return `true` from
  `isOrphanStructureSquare(square)` above, and the `||` short-circuit
  means adjacency never runs for them

### Condition

```
square != null
&& square.associatedBuilding != null
```

`associatedBuilding` is set only by the world generator, never on
player-built clusters. That alone classifies a tile as "real vanilla
building" for our purpose. An earlier drop-Z anchor filter was redundant
and too strict for upper-floor walls whose column has no
`getBuilding` / `roofHideBuilding` set below (encountered e.g. in
emptyoutside-adjacent cells).

### Advice

`@Patch.OnExit`, `@Patch.Return(readOnly = false) boolean result`.
Early returns: `!result`, `!PeekAViewMod.enabled`,
`!PeekAViewMod.cutawayEnabled`, `!fixB42Adjacency`,
`PeekAViewMod.isCameraPlayerIndoor()` (see [Why outdoor only](#why-outdoor-only)),
`square == null`, `square.associatedBuilding == null`. Otherwise
`result = false`.

---

## Why outdoor only

Both B42-fix patches (`Patch_shouldCutaway` and
`Patch_isAdjacentToOrphanStructure`) bail out when the camera player
is inside a room, letting vanilla cutaway flow through unchanged.

The fix is a workaround for a vanilla bug, not a perfect filter:
`associatedBuilding != null` and the per-cluster distance gate are
deliberately coarse. Their known side effect is that player-built
tiles next to a vanilla building can become visible at certain
camera angles, most noticeably when the camera player is inside the
adjacent vanilla building (player-built railings on the upper floor
showing through cut walls etc.).

Indoor that side effect is the dominant artifact, and the underlying
bug the fix patches is rare at the vanilla cutaway range that indoor
scenes fall back to. Outdoor the calculus reverses: the bug is
amplified by the extended POI raster, and the side effect's
visibility is muted because the player is rarely staring directly at
their own player-built tiles from the right angle.

Gating the fix on `IsoCamera.frameState.camCharacterSquare.isInARoom()`
keeps it where it pays off and removes it where it costs.
