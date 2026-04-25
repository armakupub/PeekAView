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
The feature is on by default; users can disable it via the `Tree fade`
section's `Enable` tickbox.

## Vanilla baseline

Vanilla `FBORenderCell.isTranslucentTree` returns `true` only when the
tree sits in the SE quadrant of the camera (`tree.x >= camX &&
tree.y >= camY`). `IsoTree.fadeAlpha` only steps **down** when
`renderFlag == true` — so without that flag, our extended quadrants
never start to fade.

`FBORenderCell.calculateObjectsObscuringPlayer` builds the list of
tiles whose solid objects (trees included) get a 0.66 fadeAlpha
**ceiling** via `calculateObjectTargetAlpha_NotDoorOrWall`. Without our
ceiling override the fade-up snaps trees back to full opacity instead
of easing.

## Quadrant geometry

Three relevant cases for `(dx, dy) = (tree.x - camX, tree.y - camY)`:

| Quadrant | Sign | Status |
|---|---|---|
| SE (`dx>0, dy>0`) | both positive | **vanilla** — already faded |
| NE (`dx>0, dy<0`) | + / − | **PeekAView fades** |
| SW (`dx<0, dy>0`) | − / + | **PeekAView fades** |
| NW (`dx<0, dy<0`) | both negative | left opaque — user scope ("looking AT the tree") |
| Diagonal axis (`dx==0` or `dy==0`) | one zero | left vanilla — minimal overlap, fading these tends to fade trees the user is walking toward |

## Range shape: Diamond, not Box

All three tree-fade-related patches (`Patch_isTranslucentTree`,
`Patch_calculateObjectsObscuringPlayer`, `Patch_DrawStencilMask` in
`Patch_IsoCell`) use the same Diamond-envelope test:

```java
if (adx + ady > PeekAViewMod.treeFadeRange) // out of range
```

A Box test (`max(adx, ady)`) would let trees in the diagonal corners
trip `renderFlag = true` from `isTranslucentTree` while
`calculateObjectsObscuringPlayer` (Diamond) does not append the
matching ceiling location and `DrawStencilMask` (Diamond) does not
extend the stencil mask. Result: the tree fades down, but its
half-transparent sprite fails the stencil test → invisible/edge-glitch.
Diamond on all three keeps the three patches consistent.

## Patch 1 — `isTranslucentTree`

**Patched method:** `FBORenderCell.isTranslucentTree(IsoObject)`

`@Patch.OnExit` advice with mutable return:

```java
@Patch.OnExit
public static void exit(@Patch.Argument(0) IsoObject object,
                        @Patch.Return(readOnly = false) boolean result)
```

Bails on:
- `result == true` (vanilla already said yes — nothing to add)
- `!fadeNWTrees` or `!isActiveTreeFadeForCurrentRenderPlayer()`
- `!(object instanceof IsoTree)`
- `object.square == null`

Position read via `IsoCamera.frameState.camCharacterX/Y` (camera-anchor
character, distinct per split-screen pass). Diamond range gate. If in
fade-quadrant and within range, `result = true` → vanilla treats the
tree as translucent for this render.

LOS untouched — `IsoTree` is not in `LosUtil` / `specialObjects`, so
this is purely a render-layer change.

## Patch 2 — `calculateObjectsObscuringPlayer` (with frame-cache)

**Patched method:**
`FBORenderCell.calculateObjectsObscuringPlayer(int playerIndex, ArrayList locations)`

Adds tiles in the NE/SW fade-quadrants (Diamond-enveloped) to the
`locations` ArrayList so vanilla's downstream
`calculateObjectTargetAlpha_NotDoorOrWall` returns 0.66 for solid
objects there — the fadeAlpha ceiling for our extended trees.

### Player position: `getX/Y/Z`, not `getCurrentSquare()`

```java
int px = PZMath.fastfloor(player.getX());
int py = PZMath.fastfloor(player.getY());
int pz = PZMath.fastfloor(player.getZ());
```

`player.getCurrentSquare()` returns `null` while the player is in a
vehicle, which would silently disable the ceiling override during
driving — observed bug: tree-fade visible only after dismounting.
`getX/Y/Z` returns world coordinates that work in and out of vehicles.

### Frame cache (Item 3 from perf-backlog)

The output is a deterministic function of `(range, px, py, pz)`:

```java
public static volatile int cacheRange = Integer.MIN_VALUE;
public static volatile int cachePx, cachePy, cachePz = ...;
public static final ArrayList<Location> cachedLocations = new ArrayList<>(420);

if (range != cacheRange || px != cachePx
        || py != cachePy || pz != cachePz) {
    rebuildCache(px, py, pz, range);
}
```

JFR before the cache: `IsoGameCharacter$Location` was the **#1
allocator at 74.55% pressure** under driving load. JFR after: Location
is not in the top-17 anymore (cache hit avoids the 420 `new Location()`
calls per frame).

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
allocates a fresh `Object[N]` mirror of `cachedLocations` every frame —
JFR after Item 3 (cache only) showed that single Object[] allocation
dominating heap pressure at 86%. The manual loop bypasses `toArray`
entirely; `ensureCapacity` collapses the per-add growth chain into a
single up-front grow.

### Why `rebuildCache` must be `public`

`@Patch` advice is bytecode-inlined into the target class
(`FBORenderCell`) at runtime. A `private` helper called from inlined
advice throws `IllegalAccessError` at runtime (`class FBORenderCell
tried to access private method ...`). Observed JFR: 10,845
IllegalAccessErrors in 120s, the catch block's `printStackTrace`
exploded `ZoneInfo`/`byte[]` allocations to 77% of total pressure
**and** silently disabled the cache because every miss bailed before
populating `cachedLocations`. Lesson: **every helper called from
inlined advice must be at least package-private; the same field/method
visibility rule that applies to direct field reads applies to method
calls**.

## Performance baseline

Measured on a 120s straight-line driving JFR run (Rosewood→Muldraugh,
range=20, max driving threshold), comparing baseline (`fadeNWTrees=off`)
vs. fully-optimized (`fadeNWTrees=on`, all caches active):

| Metric | OFF | ON (cached) | Δ |
|---|---:|---:|---:|
| Top-Allocator | `Class[]` 76% (JIT background) | `ArrayList$SubList` 58% (vanilla UI Lua) | — |
| `Location` allocator | not in top | not in top | ✓ |
| Total GC pause | 64.3 ms | 90.5 ms | +26 ms |
| Median GC pause | 12.6 ms | 11.3 ms | ≈ 0 |
| `IllegalAccessError` count | 0 | 0 | ✓ |

CPU hot-method deltas all within ±10% of baseline noise. Tree-fade adds
~26ms total GC-pressure across 120s — one extra short young-gen GC,
not a steady stutter source. Median per-pause is identical to baseline.
