# Patch_FBORenderCell

Four `@Patch` inner classes on
`zombie.iso.fboRenderChunk.FBORenderCell`:

1. **`Patch_isTranslucentTree`** — tree-fade range extension and
   bidirectional speed-proportional fade snap (this document, sections
   below).
2. **`Patch_renderInternal`** — FBO render-pass swap for the stair
   view feature.
3. **`Patch_isPotentiallyObscuringObject`** — gate on the upper-floor
   z during the stair render window.
4. **`Patch_renderPlayers`** — inverted swap so the player sprite
   draws at its real position while the rest of the chunk renders the
   upper floor.

The three stair patches (2–4) are documented in
[`Stair_feature.md`](Stair_feature.md). This document covers only
`Patch_isTranslucentTree`.

**File:** `src/pzmod/peekaview/Patch_FBORenderCell.java`

## Patch_isTranslucentTree

Extends vanilla's tree-fade so trees the character is looking toward
become translucent within `PeekAViewMod.treeFadeRange`. Trees behind
the player route to a refade-snap branch instead, so a vehicle driving
past doesn't leave a long ghost-trail of half-faded trees behind it.
Together with `Patch_DrawStencilMask` (in `Patch_IsoCell`) this lets
distant zombies whose tile the character can already see stop hiding
behind opaque tree sprites.

**Gates (in order):**
- `PeekAViewMod.fadeNWTrees == true` (tree-fade section toggle)
- `PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()` (master
  enable; tree-fade gate doesn't honor stance or vehicle)
- `!PeekAViewMod.isCameraPlayerIndoor()` (outdoor only, mirrors
  `Patch_DrawStencilMask`)
- `object instanceof IsoTree` and `object.square != null`

For coordinate conventions and render order see
[`iso-geometry.md`](iso-geometry.md).

## Vanilla baseline

Vanilla `FBORenderCell.isTranslucentTree` (decompiled at line ~1765)
runs three gates and returns `true` only if all pass:

1. `renderLevels.inStencilRect` — chunk-level early exit.
2. **Per-tree stencil-bbox check** against `cell.stencilX1..Y2` (set
   by `IsoCell.DrawStencilMask:450-453`, sized to the vanilla circular
   stencil texture, ~4-5 tile radius around the player). Trees outside
   that bbox are dropped before any quadrant logic runs.
3. SE-quadrant test:
   `square.x >= camCharacterX && square.y >= camCharacterY`.
   `camCharacterX/Y` is set in `IsoCamera$FrameState.set()` directly
   to `camCharacter.getX()/getY()` with no vehicle offset, no aim-pan.

`IsoTree.fadeAlpha` only steps **down** when `renderFlag == true`, so
without our flag flip the extended quadrants never start to fade.

## Patch flow

**Patched method:** `FBORenderCell.isTranslucentTree(IsoObject)`

`@Patch.OnExit` advice with mutable return:

```java
@Patch.OnExit
public static void exit(@Patch.Argument(0) IsoObject object,
                        @Patch.Return(readOnly = false) boolean result)
```

After the gates pass, the tile is classified into one of three
buckets used by the speed-snap below. The classification skips
entirely when vanilla already returned `true` (the SE-bbox path),
in which case `inZone = true` directly.

```
1. clearlyBehind  (back ~140° cone, dot > 0.34)
2. inZone         (Euclidean radius ≤ slider AND tree-fade cone)
3. neither        (vanilla owns the result and the alphaStep)
```

Priority order: **clearlyBehind first**, then `inZone`. Vehicle's
tree-fade cone is uncapped (360°), so a tile behind the player
satisfies the cone test as well; checking clearlyBehind first routes
those tiles to refade instead of let the cone-test override them
back into a down-fade.

```java
boolean inZone = result;
boolean clearlyBehind = false;
if (!result) {
    int dx = object.square.x - PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
    int dy = object.square.y - PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
    if (dx == 0 && dy == 0) return;

    if (PeekAViewMod.isTileClearlyBehindCameraPlayer(object.square)) {
        clearlyBehind = true;
    } else {
        int range = PeekAViewMod.treeFadeRange;
        if (dx * dx + dy * dy <= range * range
            && PeekAViewMod.isTileInTreeFadeCone(object.square)) {
            inZone = true;
            result = true;
        }
    }
}
```

Distance is **Euclidean** (`dx*dx + dy*dy <= range*range`), not
Manhattan. Manhattan diamonds shrink the effective reach asymmetrically
on diagonal travel; the circle is direction-symmetric and matches the
"how far the player can look around trees" mental model.

For walking, `isTileInTreeFadeCone` collapses to the dynamic vanilla
cone (vanilla returns `≤ 0` on foot, modified by Eagle-Eyed / fatigue
/ panic / drunk). For vehicles, vanilla returns `1.0` and the gate
becomes 360°.

## Bidirectional speed snap

After the classification, a per-call `fadeAlpha` step fires above
`MIN_KMH`. Same cubic curve in both directions:

```java
float speed = PeekAViewMod.currentVehicleSpeedKmh;
float minBoost = PeekAViewMod.TREE_FADE_SNAP_MIN_KMH;     // 10
if (speed > minBoost) {
    IsoTree tree = (IsoTree) object;
    float minAlpha = 0.25f;
    float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH; // 50
    float t   = speed >= cap ? 1.0f : (speed - minBoost) / (cap - minBoost);
    float step = (1.0f - minAlpha) * t * t * t;
    if (inZone && tree.fadeAlpha > minAlpha) {
        tree.fadeAlpha -= step;
        if (tree.fadeAlpha < minAlpha) tree.fadeAlpha = minAlpha;
    } else if (clearlyBehind && tree.fadeAlpha < 1.0f) {
        tree.fadeAlpha += step;
        if (tree.fadeAlpha > 1.0f) tree.fadeAlpha = 1.0f;
    }
}
```

`SPEED_CAP_KMH = 50` is the snap threshold: at and above 50 km/h the
per-call step covers the full 0.75 range in one call, so a single
`isTranslucentTree` call drops `fadeAlpha` from 1.0 to the floor (or
raises it from 0.25 to 1.0 in the clearlyBehind branch).

`MIN_KMH = 10` is the floor below which the boost is skipped and
vanilla's per-frame `alphaStep = 0.045 * thirtyFPSMultiplier` owns
the animation. The floor exists because `isTranslucentTree` fires
6-10× per tree per frame, and even tiny `t³` decrements compound
across that many calls. Below MIN, walking-pace gradients stay
smooth via vanilla's 0.55 s rate.

Between MIN and CAP the cubic curve ramps the per-call decrement:
~0.012 at 15 km/h, ~0.094 at 30 km/h, ~0.36 at 45 km/h.

### Refade vs. fade-down symmetry

The `clearlyBehind` branch does **not** set `result = true`. Vanilla's
`renderFlag` follows the (unmodified) `false` return, vanilla's
alphaStep pushes `fadeAlpha` upward at the 0.55 s rate. Our snap
adds to that on top, so at high speed the tree behind the vehicle
solidifies in one or two frames; at low speed vanilla's rate dominates
and the rise stays smooth.

The fade-down side sets `result = true`. Vanilla pushes `fadeAlpha`
downward via the same alphaStep, our snap accelerates. Both directions
use the same curve, so a tree passing through the cone-edge boundary
(dot crossing 0.34) sees the snap reverse at the same intensity.

### Compound-step safety

The advice runs once per `isTranslucentTree` call. The vanilla
caller invokes it 6-10× per tree per frame. Each call modifies
`fadeAlpha`, so the per-frame total is `step` × call-count. The
floor / ceiling clamps (`minAlpha`, `1.0f`) prevent overshoot on
either side; the `MIN_KMH = 10` gate prevents below-pace
compounding from drifting `fadeAlpha` perceptibly.

`tree.fadeAlpha` is a `public float` accessible from the inlined
advice without `IllegalAccessError`. Indoor `minAlpha = 0.05` is
ignored — drivers are always outdoors. `currentVehicleSpeedKmh` is
`0f` when not in a vehicle, gating the whole snap block to drivers
only.
