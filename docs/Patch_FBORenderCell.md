# Patch_FBORenderCell

Patches `zombie.iso.fboRenderChunk.FBORenderCell.isTranslucentTree`
to extend vanilla's tree-fade so trees the character can see past
become translucent within `PeekAViewMod.treeFadeRange`. Together with
`Patch_DrawStencilMask` (in `Patch_IsoCell`) this makes distant
zombies whose tile the character can already see stop hiding behind
opaque tree sprites.

**File:** `src/pzmod/peekaview/Patch_FBORenderCell.java`
**Gates (in order):**
- `PeekAViewMod.fadeNWTrees == true` (master toggle)
- `PeekAViewMod.isActiveTreeFadeForCurrentRenderPlayer()` (stance,
  driving-speed, vehicle-context)
- `!PeekAViewMod.isCameraPlayerIndoor()` (outdoor only, mirrors
  `Patch_DrawStencilMask`)

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
   to `camCharacter.getX()/getY()` — no vehicle offset, no aim-pan.

`IsoTree.fadeAlpha` only steps **down** when `renderFlag == true`, so
without our flag flip the extended quadrants never start to fade.

## Patch — `isTranslucentTree`

**Patched method:** `FBORenderCell.isTranslucentTree(IsoObject)`

`@Patch.OnExit` advice with mutable return:

```java
@Patch.OnExit
public static void exit(@Patch.Argument(0) IsoObject object,
                        @Patch.Return(readOnly = false) boolean result)
```

Bails on any of:
- `!fadeNWTrees` or `!isActiveTreeFadeForCurrentRenderPlayer()`
- `isCameraPlayerIndoor()`
- `!(object instanceof IsoTree)`
- `object.square == null`

Then two paths share one method:

**Path A — vanilla returned `false`.** Compute `(dx, dy)` from
`camCharacterX/Y` to `square.x/y`. Skip if origin tile, outside the
diamond range, or outside the camera player's forward cone
(`PeekAViewMod.isTileInCameraPlayerCone`). Otherwise `result = true`.

Out of cone we leave vanilla's value alone, so vanilla's close-zone
SE-only fade runs untouched. In cone, the flip overrides vanilla
across the diamond.

**Path B — vanilla returned `true`** (SE-inside-bbox). `result`
final; fall through to speed-snap so SE-bbox trees and our
extended-quadrant trees respond to driving speed identically.

## Speed-proportional fade boost

After `result == true`, applies a per-call decrement to
`tree.fadeAlpha`:

```java
float speed = PeekAViewMod.currentVehicleSpeedKmh;
float minBoost = PeekAViewMod.TREE_FADE_SNAP_MIN_KMH;     // 10
if (speed > minBoost && tree.fadeAlpha > 0.25f) {
    float cap = PeekAViewMod.TREE_FADE_SNAP_SPEED_CAP_KMH; // 80
    float t   = speed >= cap ? 1.0f : (speed - minBoost) / (cap - minBoost);
    float decrement = (1.0f - 0.25f) * t * t * t;
    tree.fadeAlpha -= decrement;
    if (tree.fadeAlpha < 0.25f) tree.fadeAlpha = 0.25f;
}
```

Vanilla's per-frame `alphaStep = 0.045 * thirtyFPSMultiplier` in
`IsoTree.render():302` takes ~0.55 s real-time to ease 1.0 → 0.25 —
matches walking pace. At driving speeds vanilla lags: 70 km/h covers
`treeFadeRange = 20` in ~1 s, 100 km/h leaves the tree half-opaque
as the bumper passes.

Below `MIN_KMH = 10` the boost is skipped; vanilla owns the fade.
This floor exists because `isTranslucentTree` fires 6-10× per tree
per frame, and even tiny `t³` decrements compound across that many
calls to a perceptible total — felt instant at speeds where the
speedometer needle barely moved.

Between `MIN_KMH` and `CAP_KMH` the cubic curve ramps the per-call
decrement: ~0.007 at 25 km/h, ~0.09 at 45 km/h, ~0.36 at 65 km/h.
At `t = 1` (≥ cap) the decrement covers the full 0.75 range in one
call.

An earlier lerp form (`fadeAlpha += (0.25 - fadeAlpha) * t`)
converged geometrically: with the advice firing 2-4× per frame, even
t = 0.17 (5 km/h) collapsed the gap by ~50% per frame.

Constraints:

- Steps **down** only. Range-exit / cone-exit uses vanilla's
  fade-up step.
- `tree.fadeAlpha` is a `public float`, accessible from the inlined
  advice without `IllegalAccessError`. Indoor `minAlpha = 0.05` is
  ignored — drivers are always outdoors.
- `currentVehicleSpeedKmh` is `0f` when not in a vehicle, gating the
  whole block.
