# Patch_FBORenderCell

Two patches on `zombie.iso.fboRenderChunk.FBORenderCell` that extend
vanilla's tree-fade so trees the character can see past become
translucent within `PeekAViewMod.treeFadeRange`. Together they make
distant zombies whose tile the character can already see stop hiding
behind opaque tree sprites.

**File:** `src/pzmod/peekaview/Patch_FBORenderCell.java`
**Gate:** both patches early-return unless
`PeekAViewMod.fadeNWTrees == true` **and**
`PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()` returns true.

For coordinate conventions, render order, and why the fade covers all
quadrants see [`iso-geometry.md`](iso-geometry.md).

## Vanilla baseline

Vanilla `FBORenderCell.isTranslucentTree` (decompiled at line ~1765)
runs three gates and returns `true` only if all pass:

1. `renderLevels.inStencilRect` — chunk-level early exit.
2. **Per-tree stencil-bbox check** against `cell.stencilX1..Y2` (set
   by `IsoCell.DrawStencilMask:450-453`, sized to the vanilla circular
   stencil texture, ~4-5 tile radius around the player). Trees outside
   that bbox are dropped before any quadrant logic runs — vanilla
   therefore translates only trees in the inner ~5 tile radius, not
   "anywhere in the SE half-plane". Our patches extend coverage past
   this by writing additional stencil pixels for distant tiles
   (`Patch_DrawStencilMask`) and flipping `isTranslucentTree`'s return
   value when vanilla would have returned `false` due to the bbox
   miss.
3. SE-quadrant test:
   `square.x >= camCharacterX && square.y >= camCharacterY`.
   `camCharacterX/Y` is set in `IsoCamera$FrameState.set()` directly
   to `camCharacter.getX()/getY()` — no vehicle offset, no aim-pan.
   For the rendering player it equals `player.getX()/getY()`.

`IsoTree.fadeAlpha` only steps **down** when `renderFlag == true`, so
without that flag our extended quadrants never start to fade.

`FBORenderCell.calculateObjectsObscuringPlayer` builds the list of
tiles whose solid objects (trees included) get a 0.66 fadeAlpha
**ceiling** via `calculateObjectTargetAlpha_NotDoorOrWall`. Without
our ceiling override the fade-up snaps trees back to full opacity
instead of easing.

## Fade scope: the full diamond

Both tree-fade patches mark every tile inside `treeFadeRange` (Diamond
envelope `|dx| + |dy| <= range`) as fade-eligible — every quadrant,
both axes, excluding only the player tile itself
(`dx == 0 && dy == 0`).

The LOS gate inside `Patch_DrawStencilMask` (`sq.isCanSee(pidx)`)
suppresses fade on tiles outside the rendering player's active
visibility cone, so trees behind walls or in fog of war stay opaque
even though the patches here flag them. Rationale for fading all four
quadrants — including NW which is behind the player in render order —
is documented in [`iso-geometry.md`](iso-geometry.md).

All three tree-fade patches share the same Diamond shape. A Box test
(`max(adx, ady)`) would let trees in the diagonal corners trip
`renderFlag = true` without matching ceiling locations or stencil
coverage. Diamond on all three keeps the patches consistent.

## Patch 1 — `isTranslucentTree`

**Patched method:** `FBORenderCell.isTranslucentTree(IsoObject)`

`@Patch.OnExit` advice with mutable return:

```java
@Patch.OnExit
public static void exit(@Patch.Argument(0) IsoObject object,
                        @Patch.Return(readOnly = false) boolean result)
```

Bails on any of:
- `!fadeNWTrees` or `!isActiveTreeFadeForCurrentRenderPlayer()`
- `!(object instanceof IsoTree)`
- `object.square == null`

Then two paths share one method:

**Path A — vanilla returned `false`.** Compute `(dx, dy)` from
`camCharacterX/Y` to `square.x/y`. Skip if origin tile, skip if
outside Diamond range, otherwise `result = true` to flip vanilla's
output.

**Path B — vanilla returned `true`** (SE-inside-bbox). Skip the
quadrant logic; `result` already final. Continue to the speed-snap
step. Running the snap on this path keeps the fade behavior
symmetric: SE-bbox trees and our extended-quadrant trees both
respond to driving speed identically.

## Speed-proportional fade boost

After Path A or B has settled `result == true`, the patch applies a
speed-proportional fade-down step on `tree.fadeAlpha` directly:

```java
float speed = PeekAViewMod.currentVehicleSpeedKmh;
if (speed > 0f && tree.fadeAlpha > 0.25f) {
    float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH;  // 30
    float t   = speed >= cap ? 1.0f : speed / cap;
    tree.fadeAlpha += (0.25f - tree.fadeAlpha) * t;
    if (tree.fadeAlpha < 0.25f) tree.fadeAlpha = 0.25f;
}
```

Vanilla's per-frame `alphaStep = 0.045 * thirtyFPSMultiplier` in
`IsoTree.render():302` takes ~0.55 s real-time to ease 1.0 → 0.25.
At 70 km/h ≈ 19 tile/s the player covers `treeFadeRange = 20` in ~1 s
and the fade just barely keeps up; at 100 km/h the tree is still
half-opaque when the bumper passes it. The boost runs every render
call that flips translucent, lerping toward `minAlpha = 0.25` in
proportion to current vehicle speed. At 0 km/h `t = 0` and vanilla
owns the animation. At ≥ 30 km/h `t = 1` and the tree snaps to
`minAlpha` in one frame.

Constraints:

- Steps **down** only — `(minAlpha - fadeAlpha)` is always negative
  while `fadeAlpha > minAlpha`. Range-exit / toggle-off still uses
  vanilla's fade-up step, so the tree eases back to opaque smoothly.
- `tree.fadeAlpha` is a `public float`, accessible from the inlined
  advice in `FBORenderCell` without `IllegalAccessError`. Indoor
  `minAlpha = 0.05` is ignored — drivers are always outdoors.
- `currentVehicleSpeedKmh` is set in `PeekAViewMod.refreshActiveCache`
  (which runs on the first patch call per frame for the rendering
  player). When the player is not in a vehicle the field is `0f` and
  the boost block is a no-op.

LOS untouched — `IsoTree` is not in `LosUtil` / `specialObjects`, so
this is purely a render-layer change.

## Patch 2 — `calculateObjectsObscuringPlayer` (with frame-cache)

**Patched method:**
`FBORenderCell.calculateObjectsObscuringPlayer(int playerIndex, ArrayList locations)`

Appends tiles to the `locations` ArrayList so vanilla's downstream
`calculateObjectTargetAlpha_NotDoorOrWall` returns 0.66 for solid
objects on those tiles — the fadeAlpha ceiling for our extended
trees. Combined with Patch 1, this keeps the fade-up smooth on
toggle-off and on range-exit.

### Tile-filter: only tiles with at least one IsoTree

The cached list contains only Diamond-range tiles that hold an
`IsoTree`. Vanilla downstream walks `squaresObscuringPlayer` per
frame in `squareHasFadingInObjects`, `isPotentiallyObscuringObject`,
`listContainsLocation` — empty grass/road tiles in the list contribute
no ceiling effect (no solid object to cap) but still cost vanilla one
walk-element each. Filtering at cache-rebuild time shrinks the per-
frame walk cost proportionally.

```java
public static boolean tileHasTree(IsoCell cell, int x, int y, int z) {
    IsoGridSquare sq = cell.getGridSquare(x, y, z);
    if (sq == null) return false;
    PZArrayList<IsoObject> objs = sq.getObjects();
    if (objs == null || objs.isEmpty()) return false;
    for (int i = 0, n = objs.size(); i < n; ++i) {
        if (objs.get(i) instanceof IsoTree) return true;
    }
    return false;
}
```

The check runs once per Diamond tile per cache-rebuild (i.e. on tile-
cross), not per frame. A tree being chopped while the player stands
still leaves the cache momentarily stale — the entry for the now-
empty tile remains until next cross, but it's harmless: vanilla
applies the 0.66 ceiling to nothing.

### Player position: `getX/Y/Z`, not `getCurrentSquare()`

```java
int px = PZMath.fastfloor(player.getX());
int py = PZMath.fastfloor(player.getY());
int pz = PZMath.fastfloor(player.getZ());
```

`player.getCurrentSquare()` returns `null` while the player is in a
vehicle, which would silently disable the ceiling override during
driving. `getX/Y/Z` returns world coordinates that work in and out
of vehicles.

### Frame cache

```java
public static volatile int cacheRange = Integer.MIN_VALUE;
public static volatile int cachePx, cachePy, cachePz = ...;
public static final ArrayList<Location> cachedLocations = new ArrayList<>(1300);

if (range != cacheRange || px != cachePx
        || py != cachePy || pz != cachePz) {
    rebuildCache(cell, px, py, pz, range);
}
```

Cache invalidation hooks:
- `PeekAViewMod.setRange(int)` calls `invalidateCache()` on slider
  change.
- `PeekAViewMod.setFadeNWTrees(boolean)` calls `invalidateCache()` on
  toggle so a toggle-off across a range change doesn't leak a stale
  list into a later toggle-on.
- Player tile-cross / Z-change invalidate via the cache-key check.

### Manual loop instead of `addAll(Collection)`

```java
final int n = cachedLocations.size();
locations.ensureCapacity(locations.size() + n);
for (int i = 0; i < n; ++i) {
    locations.add(cachedLocations.get(i));
}
```

`ArrayList.addAll(Collection c)` calls `c.toArray()` internally, which
allocates a fresh `Object[N]` mirror of `cachedLocations` every frame.
The manual loop bypasses `toArray` entirely; `ensureCapacity`
collapses the per-add growth chain into a single up-front grow.

### Why `rebuildCache` and `tileHasTree` are `public`

`@Patch` advice is bytecode-inlined into the target class
(`FBORenderCell`) at runtime. A `private` helper called from inlined
advice throws `IllegalAccessError` at runtime. Every helper called
from inlined advice must be at least package-private; the same
field/method visibility rule that applies to direct field reads
applies to method calls.
