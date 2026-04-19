# Patch_IsoCell

Expands the POI fan that seeds building cutaway.

**File:** `src/pzmod/peekaview/Patch_IsoCell.java`
**Patched method:** `zombie.iso.IsoCell.GetSquaresAroundPlayerSquare`

## Shape

Vanilla iterates a 10x10 raster (-4..+5) around the player with a
diamond filter of half-width 4.5 along the SE diagonal and an explicit
N/W-quadrant exclusion (`x < pxFloor && y < pyFloor`). Emits squares
into `outLeft` / `outRight` depending on `deltaY >= deltaX` /
`deltaY <= deltaX`.

We reimplement the same shape scaled to `PeekAViewMod.range`:
- `rasterSize = radius * 2 + 2`
- `DIAMOND_HALF_WIDTH = 22.5f` (scaled so the diamond covers the extended raster)
- N/W-quadrant exclusion preserved: already guarantees we only extend
  cutaway when the player stands N/W of the target.
- `deltaY >= deltaX` and `deltaY <= deltaX` both true on the diagonal
  (square emitted to both lists) — mirrors vanilla.

Runtime radius is `PeekAViewMod.range` snapshotted once per miss, clamped
to `[MIN_RANGE, MAX_RADIUS]`.

### Rejected: NE-quadrant drop

v0.1.x dropped the NE quadrant of the raster on top of the vanilla
N/W exclusion for an extra ~25% saving. Reverted: when the player stands
SW of a building, the building's near walls fall in the player's NE
quadrant — dropping them broke cutaway in exactly the case where
vanilla's short range stops covering it. The NE quadrant has to stay in
the candidate set.

## OnEnter advice

`@Patch.OnEnter(skipOn = true)` — returning `true` from `enter` skips
the original method body (we supplied the result). `false` falls through
to vanilla.

### Fallthrough conditions

- `!PeekAViewMod.isActiveForCurrentRenderPlayer()` (main gate)
- `cell == null || player == null || square == null`
- `playerIndex < 0 || playerIndex >= MAX_PLAYERS`
- Any `Throwable` (logged via `PeekAViewMod.trace`)

## Cache

Patch fires per render-pass per player (~60/s). Standing still produces
the identical raster every time.

### Invalidation

Miss key: `(cell, pxFloor, pyFloor, z)` per player slot. Invalidated on
tile cross, Z change, or cell swap. Sub-tile movement keeps the cache
valid; squares at the diamond boundary may briefly include/exclude one
tile late — visually imperceptible.

Also force-invalidated from `PeekAViewMod.setRange` (range change ⇒
raster output stale).

### Storage model: coordinates, not refs

Cache stores `(int x, int y)` pairs, not `IsoGridSquare` refs.
`IsoGridSquare` objects live in a pool (`IsoGridSquare.getNew` pops from
`isoGridSquareCache`, `discard()` pushes back). When `WorldReuser`
discards a chunk's squares asynchronously, a cached ref would point at
a recycled square now assigned to a different (x,y,z) elsewhere — silent
aliasing bug. Coordinates are primitive and race-free; on hit we
re-resolve via `cell.getGridSquare(...)` (O(1)
`chunkMap.getGridSquareDirect`, returns `null` for unloaded tiles, which
we skip).

### Per-player slots

`MAX_PLAYERS = IsoPlayer.MAX = 4`.

Split-screen alternates `IsoCamera.frameState.playerIndex` within one
wall-clock frame. Both POI call-sites
(`FBORenderCutaways.CalculatePointsOfInterest`,
`IsoCell.CalculateBuildingsToCollapse`) fire per active player. Single
shared slot → 0% hit rate with two players on different tiles. One slot
per `playerIndex` restores ~59/60 steady-state hit rate per player.

Per-player arrays:

```
cachedCell[MAX_PLAYERS]
cachedPxFloor[MAX_PLAYERS]
cachedPyFloor[MAX_PLAYERS]
cachedZ[MAX_PLAYERS]
cachedLeftX[MAX_PLAYERS][MAX_COORDS]
cachedLeftY[MAX_PLAYERS][MAX_COORDS]
cachedLeftCount[MAX_PLAYERS]
cachedRightX[MAX_PLAYERS][MAX_COORDS]
cachedRightY[MAX_PLAYERS][MAX_COORDS]
cachedRightCount[MAX_PLAYERS]
```

`MAX_RASTER_SIZE = MAX_RADIUS * 2 + 2`, `MAX_COORDS = MAX_RASTER_SIZE²`.

### Access-context constraint

`@Patch.OnEnter` advice is **inlined into `IsoCell`** bytecode. Any
non-constant field read/written by the advice must be accessible from
`IsoCell` — hence `public static final` on the array fields and `public`
on helper methods. Element mutation of `final` arrays is fine.

Compile-time constants (`RADIUS`, `DIAMOND_HALF_WIDTH`, `MAX_COORDS`)
can stay private — javac folds them into literals before the advice
ever sees a field reference.

## Wall-adjacency filter

JFR (2026-04-18) showed driving-time cost is ~100% downstream: each
emitted square seeds a full `FBORenderCutaways.cutawayVisit` pass over
every on-screen wall. The raster is only useful where it can actually
seed cutaway — adjacent to a building wall.

Rule:
- Inside `VANILLA_KEEP_RADIUS = 5` tiles (both |dx| and |dy|) → mirror
  vanilla exactly.
- Outside → drop squares that are not wall-adjacent.

### `isNearWall(sq, cell, x, y, z)`

PZ wall storage: N/W walls live on the owning tile; S wall = y+1's N;
E wall = x+1's W. Probing `sq` plus S-neighbor and E-neighbor covers
all four sides with at most three grid lookups. Short-circuits on first
hit.

```java
public static boolean isNearWall(IsoGridSquare sq, IsoCell cell, int x, int y, int z)
```

Public for the same advice-inlining reason as the cache fields.

## Line-of-sight filter

**Feature scope:** vanilla-style cutaway with longer range, not
see-through-rooms. Keep POIs in front of the first wall/window/door
from the player; drop POIs already behind one.

Only applied outside `VANILLA_KEEP_RADIUS`.

### `hasLineOfSight(cell, px, py, tx, ty, z)`

Bresenham walk from (px,py) to (tx,ty). Drops on the first crossing
(zero-crossings only). Guarded at 64 iterations — worst case ≈
RADIUS·2 steps.

### `crossesWall(cell, fx, fy, tx, ty, z)`

Bresenham steps are axis-aligned (x OR y changes per step, never both),
so four directions:

| Step | Check |
|------|-------|
| tx > fx (E) | `to.hasWestBarrier()` |
| tx < fx (W) | `from.hasWestBarrier()` |
| ty > fy (S) | `to.hasNorthBarrier()` |
| ty < fy (N) | `from.hasNorthBarrier()` |

Barrier = wall OR window OR door-wall OR door (`IsoFlagType.WallX`,
`WindowX`, `DoorWallX`, `doorX` for X ∈ {N, W}). Windows and door-walls
count — without them a window in the nearest wall lets the walk slip
into the room behind it.

## Emission

```
if (deltaY >= deltaX) → outLeft   (and cache (x,y) in leftX/leftY)
if (deltaY <= deltaX) → outRight  (and cache (x,y) in rightX/rightY)
```

Diagonal (deltaY == deltaX) emits to both. Matches vanilla.
