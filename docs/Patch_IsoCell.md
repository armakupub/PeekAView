# Patch_IsoCell

Two patches on `zombie.iso.IsoCell`:

1. **`Patch_GetSquaresAroundPlayerSquare`** — expands the POI fan that
   seeds building cutaway (always-on, governs the `range` slider).
2. **`Patch_DrawStencilMask`** — extends the stencil mask that gates
   tree-fade visibility (gated on `fadeNWTrees` toggle, opt-in).

**File:** `src/pzmod/peekaview/Patch_IsoCell.java`

For coordinate conventions and the stencil/sprite interaction see
[`iso-geometry.md`](iso-geometry.md).

# Patch 1 — `Patch_GetSquaresAroundPlayerSquare`

**Patched method:** `zombie.iso.IsoCell.GetSquaresAroundPlayerSquare`

## Vanilla pass-through at MIN_RANGE

```java
if (PeekAViewMod.range <= PeekAViewMod.MIN_RANGE) return false;
```

Returning `false` from `@Patch.OnEnter(skipOn = true)` lets vanilla's
own implementation run unmodified. At `range == 5` this guarantees
1:1 vanilla behavior — vanilla's exact 10×10 raster with diamond
half-width 4.5. The patch only runs at `range > MIN_RANGE`.

## Shape (range > MIN_RANGE)

Vanilla iterates a 10×10 raster (-4..+5) around the player with a
diamond filter of half-width 4.5 along the SE diagonal and an explicit
N/W-quadrant exclusion (`x < pxFloor && y < pyFloor`). Emits squares
into `outLeft` / `outRight` depending on `deltaY >= deltaX` /
`deltaY <= deltaX`.

We reimplement the same shape scaled to `PeekAViewMod.range`:
- `rasterSize = radius * 2 + 2`
- `DIAMOND_HALF_WIDTH = 22.5f` (sized for max radius)
- N/W-quadrant exclusion preserved: only emit POIs where the player
  stands N/W of the target.
- `deltaY >= deltaX` and `deltaY <= deltaX` both true on the diagonal
  (square emitted to both lists) — mirrors vanilla.

Runtime radius is `PeekAViewMod.range` snapshotted once per miss,
clamped to `[MIN_RANGE, MAX_RADIUS]`.

## Slider value vs. effective trigger range

The slider's user-visible effect is **direction-dependent** because
of how vanilla's downstream `CalculateBuildingsToCollapse` consumes
the POI list.

For each POI vanilla calls `GetBuildingsInFrontOfCharacter`, which
reads `square.getOcclusionData().getBuildingsCouldBeOccluders(...)`.
Per-square occlusion data is computed by `LazyInitializeSoftOccluders`
projecting **diagonally SE through Z-levels** — `(x+1, y+1, z+0)`,
`(x+2, y+2, z+0)`, then `(x+3+3·k, y+3+3·k, z+1+k)` up to
`maxHeight`. So a player POI at `(px, py)` already detects buildings
~14 tiles SE diagonally via this Z-projection, with no help from the
mod.

When the building is **east of the player** (W-wall facing west,
player approaches from west toward east): the player's natural
SE-projection covers it via the Z-stair to ~14 tiles. Slider 5–13
adds POIs that don't expose anything vanilla wasn't already covering,
so the user sees no per-step effect until ~slider 14, where extension
POIs reach far enough that their own SE-projections cover walls
beyond vanilla's `dx=14`.

When the building is **iso-north of the player** (NW-bound approach
in world coords, "behind the player from camera view"): vanilla's
SE-projection from the player POI doesn't reach NW-direction walls.
Each extension POI added by our raster has its own SE-projection
covering buildings further into the NW direction relative to the
player — so each `+1` to the slider yields `+1` tile of effective
trigger range, directly visible.

The slider is therefore most impactful in directions where vanilla's
SE-Z-projection doesn't reach — primarily NW, N, W approaches. In
the SE direction it duplicates vanilla until ~slider 14.

## OnEnter advice

`@Patch.OnEnter(skipOn = true)` — returning `true` from `enter` skips
the original method body (we supplied the result). `false` falls
through to vanilla.

### Fallthrough conditions

- `!PeekAViewMod.isActiveCutawayForCurrentRenderPlayer()` (cutaway gate)
- `cell == null || player == null || square == null`
- `playerIndex < 0 || playerIndex >= MAX_PLAYERS`
- `PeekAViewMod.range <= MIN_RANGE` — vanilla pass-through
- Any `Throwable` (logged via `PeekAViewMod.trace`)

## Cache

Patch fires per render-pass per player (~60/s). Standing still
produces the identical raster every time.

### Invalidation

Miss key: `(cell, pxFloor, pyFloor, z)` per player slot. Invalidated
on tile cross, Z change, or cell swap. Sub-tile movement keeps the
cache valid; squares at the diamond boundary may briefly include /
exclude one tile late — visually imperceptible.

Also force-invalidated from `PeekAViewMod.setRange` (range change ⇒
raster output stale).

### Storage model: coordinates, not refs

Cache stores `(int x, int y)` pairs, not `IsoGridSquare` refs.
`IsoGridSquare` objects live in a pool (`IsoGridSquare.getNew` pops
from `isoGridSquareCache`, `discard()` pushes back). When
`WorldReuser` discards a chunk's squares asynchronously, a cached ref
would point at a recycled square now assigned to a different (x,y,z)
elsewhere — silent aliasing bug. Coordinates are primitive and
race-free; on hit we re-resolve via `cell.getGridSquare(...)` (O(1)
`chunkMap.getGridSquareDirect`, returns `null` for unloaded tiles,
which we skip).

### Per-player slots

`MAX_PLAYERS = IsoPlayer.MAX = 4`.

Split-screen alternates `IsoCamera.frameState.playerIndex` within one
wall-clock frame. Both POI call-sites
(`FBORenderCutaways.CalculatePointsOfInterest`,
`IsoCell.CalculateBuildingsToCollapse`) fire per active player. Single
shared slot → 0% hit rate with two players on different tiles. One
slot per `playerIndex` restores ~59/60 steady-state hit rate per
player.

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

`MAX_RASTER_SIZE = MAX_RADIUS * 2 + 2`,
`MAX_COORDS = MAX_RASTER_SIZE²`.

### Access-context constraint

`@Patch.OnEnter` advice is **inlined into `IsoCell`** bytecode. Any
non-constant field read/written by the advice must be accessible from
`IsoCell` — hence `public static final` on the array fields and
`public` on helper methods. Element mutation of `final` arrays is
fine.

Compile-time constants (`RADIUS`, `DIAMOND_HALF_WIDTH`, `MAX_COORDS`)
can stay private — javac folds them into literals before the advice
ever sees a field reference.

## Wall-adjacency filter

Each emitted POI seeds a full `FBORenderCutaways.cutawayVisit` pass
over every on-screen wall, so the raster is only useful where it can
actually seed cutaway — adjacent to a building wall.

Rule:
- Inside `VANILLA_KEEP_RADIUS = 5` tiles (both |dx| and |dy|) →
  mirror vanilla exactly.
- Outside → drop squares that are not wall-adjacent.

### `isNearWall(sq, cell, x, y, z)`

PZ wall storage: N/W walls live on the owning tile; S wall = y+1's N;
E wall = x+1's W. Probing `sq` plus S-neighbor and E-neighbor covers
all four sides with at most three grid lookups. Short-circuits on
first hit.

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

Bresenham steps are axis-aligned (x OR y changes per step, never
both), so four directions:

| Step | Check |
|------|-------|
| tx > fx (E) | `to.hasWestBarrier()` |
| tx < fx (W) | `from.hasWestBarrier()` |
| ty > fy (S) | `to.hasNorthBarrier()` |
| ty < fy (N) | `from.hasNorthBarrier()` |

Barrier = wall OR window OR door-wall OR door (`IsoFlagType.WallX`,
`WindowX`, `DoorWallX`, `doorX` for X ∈ {N, W}). Windows and
door-walls count — without them a window in the nearest wall lets
the walk slip into the room behind it.

## Emission

```
if (deltaY >= deltaX) → outLeft   (and cache (x,y) in leftX/leftY)
if (deltaY <= deltaX) → outRight  (and cache (x,y) in rightX/rightY)
```

Diagonal (deltaY == deltaX) emits to both. Matches vanilla.

---

# Patch 2 — `Patch_DrawStencilMask`

**Patched method:** `zombie.iso.IsoCell.DrawStencilMask`
**Gate:** `fadeNWTrees == true` (opt-in tickbox).

## Why a stencil mask matters for tree-fade

Vanilla `DrawStencilMask` renders a circular alpha texture
(`media/mask_circledithernew.png`) once per frame centered on the
player. The stencil buffer it writes **caps where translucent passes
(tree fade, wall cutaway) can render on screen** — outside the mask,
nothing. The mask radius is roughly the inner ~5 tiles, so any
extended fade we set up in `Patch_FBORenderCell` would render opaque
beyond that radius without our extension.

`IsoTree.render()` renders tall trees in a two-pass stencil effect:
- `GL_NOTEQUAL` pass: render where stencil ≠ 128, full alpha → opaque.
- `GL_EQUAL` pass: render where stencil = 128, alpha = `fadeAlpha` →
  translucent.

The test is **per pixel**, not per sprite. A sprite that overshoots
the stencil mask gets cut: pixels inside the mask fade, pixels
outside stay opaque. The mask therefore needs to be sized to cover
where each tree sprite's pixels actually land on screen, not just
where its base tile is.

## What we add

After vanilla's circle runs (`@Patch.OnExit`), we append per-tile
stencil writes for every LOS-visible tile in the full diamond around
the player, up to `PeekAViewMod.treeFadeRange`. The write is additive
via `glStencilOp(7680, 7680, 7681)` with
`glStencilFunc(519, 128, 255)` — `GL_REPLACE` writes ref `128`,
layered on top of vanilla's circle.

The resulting mask shape **follows the character's actual line of
sight** — no fade through walls, no ghost fade in unseen areas —
because each per-tile write is gated on `sq.isCanSee(playerIndex)`.

## Player-index source

```java
int pidx = IsoCamera.frameState.playerIndex;
```

Not `IsoPlayer.getPlayerIndex()`. The frame-state value is the
currently-rendering player (set per render-pass by the engine), and
is what every other patch in the mod uses; the static getter returns
the local active player and would write the stencil mask for the
wrong player in split-screen.

## Player position: `getX/Y/Z`, not `getCurrentSquare()`

`getCurrentSquare()` returns `null` while the player is in a vehicle.
Using the int-floor of `player.getX/Y/Z()` keeps the mask extension
working in and out of vehicles.

## Geometry

```
Diamond range:   if (adx + ady > range) skip
Inner skip:      if (adx <= 4 && ady <= 4) skip   ← vanillaSkip = MIN_RANGE-1
Origin skipped via the inner-skip rule (adx == 0 && ady == 0 ≤ 4).
No quadrant filter — every tile in the diamond gets a stencil write.
```

`vanillaSkip = MIN_RANGE - 1 = 4` because vanilla's circular stencil
already covers roughly the inner 4 tiles around the player. Skipping
that area avoids overlapping our per-tile dither with vanilla's
gradient — the two independent patterns produce a flickery moiré at
sub-pixel camera shifts.

## LOS gate — `sq.isCanSee(playerIndex)`

Single LOS check in the entire tree-fade chain. Drops the per-tile
stencil write if the rendering player has no current line of sight
to the tile. Effects:

- Trees behind walls / inside fog of war: no stencil pixel written →
  `GL_EQUAL` pass fails → tree renders opaque via `GL_NOTEQUAL` pass,
  even though `Patch_FBORenderCell`'s patches flagged it translucent.
  This is the design (no see-through-walls).
- The other two tree-fade patches do not gate on LOS — they
  blanket-apply within range. The stencil mask is therefore the
  authoritative gate on what the user sees as faded.
- PZ's `isCanSee` is forward-biased — the visibility cone reaches
  further in the character's facing direction than behind it. NW
  trees in our full-diamond fade in/out as the player rotates, which
  matches "fade what I'm looking toward" intuitively.

## Per-tile render

```java
float sx = IsoUtils.XToScreen(tx, ty, pz, 0) - offX;
float sy = IsoUtils.YToScreen(tx, ty, pz, 0) - offY;
tex2.renderstrip((int) sx - halfW, (int) sy - tileFootprintYOffset,
        renderW, renderH, 1f, 1f, 1f, 1f, null);
```

| Constant | Value | Why |
|---|---|---|
| `renderW` | `128 * tileScale` | Wider than iso-tile footprint (64) — symmetric overshoot |
| `renderH` | `256 * tileScale` | Total mask height |
| `tileFootprintYOffset` | `192 * tileScale` | Places the patch 6 tile-heights above the tile floor and ~2 below — sized to cover any PZ tree sprite's crown |

The 128×256 dimensions overshoot the iso-tile footprint on purpose:
trees extend upward on screen from their base tile, so the stencil
needs coverage above the tile base for the tree sprite's `GL_EQUAL`
pass to pass the stencil test.

## OpenGL state

Setup:
- `glStencilMask(255)`, `enableStencilTest`, `enableAlphaTest`
- `glAlphaFunc(516, 0.1f)` — alpha cull at 0.1
- `glStencilFunc(519, 128, 255)` — always write ref 128
- `glStencilOp(7680, 7680, 7681)` — `GL_KEEP, GL_KEEP, GL_REPLACE`
- `glColorMask(false, false, false, false)` — write stencil only

Restore:
- `glColorMask(true, true, true, true)`
- `glStencilFunc(519, 0, 255)` — back to read-without-write
- `glStencilOp(7680, 7680, 7680)` — back to all-keep
- `glStencilMask(127)` — vanilla default
- `glAlphaFunc(519, 0.0f)` — disable alpha cull

`enableStencilTest()` / `enableAlphaTest()` are not explicitly
disabled — vanilla already had them enabled before our `OnExit`, and
downstream passes expect them to remain on.
